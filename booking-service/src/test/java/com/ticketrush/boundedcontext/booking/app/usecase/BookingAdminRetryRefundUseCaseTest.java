package com.ticketrush.boundedcontext.booking.app.usecase;

import static com.ticketrush.global.status.ErrorStatus.BOOKING_NOT_FOUND;
import static com.ticketrush.global.status.ErrorStatus.BOOKING_REFUND_RETRY_NOT_ALLOWED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.policy.RefundingStuckPolicy;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.shared.booking.event.RefundRequestedEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BookingAdminRetryRefundUseCaseTest {

  private BookingAdminRetryRefundUseCase bookingAdminRetryRefundUseCase;

  @Mock private BookingRepository bookingRepository;
  @Mock private EventPublisher eventPublisher;

  private static final String BOOKING_NUMBER = "BOOK-1234";
  private static final Long ADMIN_ID = 99L;
  private static final long STUCK_THRESHOLD_MINUTES = 30;
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 13, 12, 0);

  @BeforeEach
  void setUp() {
    Clock fixedClock =
        Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    bookingAdminRetryRefundUseCase =
        new BookingAdminRetryRefundUseCase(
            bookingRepository,
            eventPublisher,
            new RefundingStuckPolicy(fixedClock, STUCK_THRESHOLD_MINUTES),
            fixedClock);
  }

  private Booking bookingWithStatus(BookingStatus status) {
    return Booking.builder()
        .userId(10L)
        .performanceId(2L)
        .seatId(3L)
        .bookingNumber(BOOKING_NUMBER)
        .bookingStatus(status)
        .build();
  }

  @Test
  @DisplayName("성공: 환불에 실패한 예매를 REFUNDING으로 전환하고 RefundRequestedEvent를 발행한다")
  void execute_success() {
    // given: 환불 실패로 CONFIRMED로 복원된 예매
    Booking booking = bookingWithStatus(BookingStatus.REFUNDING);
    booking.recordRefundFailure(LocalDateTime.of(2026, 7, 10, 12, 0));
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER)).willReturn(Optional.of(booking));

    // when
    bookingAdminRetryRefundUseCase.execute(ADMIN_ID, BOOKING_NUMBER);

    // then: payment가 이벤트를 받아 PG 환불을 재실행하거나(COMPLETED) 취소 이벤트를 재발행한다(CANCELED)
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.REFUNDING);
    verify(eventPublisher)
        .publish(
            argThat(
                event ->
                    event instanceof RefundRequestedEvent refundRequested
                        && refundRequested.bookingNumber().equals(BOOKING_NUMBER)
                        && refundRequested.seatId().equals(3L)
                        && refundRequested.userId().equals(10L)
                        && refundRequested.requestedAt() != null));
  }

  @Test
  @DisplayName("성공: 임계 시간 이상 REFUNDING에 멈춘 고착 예매는 상태 전이 없이 이벤트만 재발행한다 (#397)")
  void execute_republishes_event_for_stuck_refunding() {
    // given: PG 통신 실패가 DLT로 빠져 종결 이벤트가 오지 않아 임계(30분)를 넘겨 고착된 예매
    Booking booking = bookingWithStatus(BookingStatus.REFUNDING);
    ReflectionTestUtils.setField(
        booking, "updatedAt", NOW.minusMinutes(STUCK_THRESHOLD_MINUTES + 1));
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER)).willReturn(Optional.of(booking));

    // when
    bookingAdminRetryRefundUseCase.execute(ADMIN_ID, BOOKING_NUMBER);

    // then: 이미 REFUNDING이므로 전이 없이 재발행만 한다. payment는 재수신에 멱등이라
    // 실제 환불 성공 여부에 따라 REFUNDED 또는 CONFIRMED+refundFailedAt로 수렴한다
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.REFUNDING);
    verify(eventPublisher)
        .publish(
            argThat(
                event ->
                    event instanceof RefundRequestedEvent refundRequested
                        && refundRequested.bookingNumber().equals(BOOKING_NUMBER)));
  }

  @Test
  @DisplayName("실패: 예매가 없으면 BOOKING_NOT_FOUND를 던진다")
  void execute_fail_when_booking_not_found() {
    // given
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> bookingAdminRetryRefundUseCase.execute(ADMIN_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(BOOKING_NOT_FOUND);

    verifyNoInteractions(eventPublisher);
  }

  @Test
  @DisplayName("실패: 이미 환불이 종결된 예매는 재시도할 수 없다")
  void execute_fail_when_already_refunded() {
    // given
    Booking booking = bookingWithStatus(BookingStatus.REFUNDED);
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER)).willReturn(Optional.of(booking));

    // when & then
    assertThatThrownBy(() -> bookingAdminRetryRefundUseCase.execute(ADMIN_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(BOOKING_REFUND_RETRY_NOT_ALLOWED);

    verifyNoInteractions(eventPublisher);
  }

  @Test
  @DisplayName("실패: 환불 실패 이력이 없는 정상 예매는 관리자가 강제 환불할 수 없다")
  void execute_fail_when_no_refund_failure_history() {
    // given: 이 API의 계약은 '실패 재시도'다. 오조작으로 정상 예매가 강제 취소되면 안 된다
    Booking booking = bookingWithStatus(BookingStatus.CONFIRMED);
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER)).willReturn(Optional.of(booking));

    // when & then
    assertThatThrownBy(() -> bookingAdminRetryRefundUseCase.execute(ADMIN_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(BOOKING_REFUND_RETRY_NOT_ALLOWED);

    verifyNoInteractions(eventPublisher);
  }

  @Test
  @DisplayName("실패: 임계 미달의 신선한 REFUNDING은 정상 진행 중이므로 중복 트리거할 수 없다")
  void execute_fail_when_refund_already_in_progress() {
    // given: 실패 이력이 있으나 방금 REFUNDING으로 재시도가 걸린 예매(고착 아님)
    Booking booking = bookingWithStatus(BookingStatus.REFUNDING);
    booking.recordRefundFailure(LocalDateTime.of(2026, 7, 10, 12, 0));
    booking.requestRefund();
    ReflectionTestUtils.setField(booking, "updatedAt", NOW.minusMinutes(1));
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER)).willReturn(Optional.of(booking));

    // when & then
    assertThatThrownBy(() -> bookingAdminRetryRefundUseCase.execute(ADMIN_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(BOOKING_REFUND_RETRY_NOT_ALLOWED);

    verifyNoInteractions(eventPublisher);
  }
}
