package com.ticketrush.boundedcontext.booking.app.usecase;

import static com.ticketrush.global.status.ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED;
import static com.ticketrush.global.status.ErrorStatus.BOOKING_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BookingAdminRefundUseCaseTest {

  private BookingAdminRefundUseCase bookingAdminRefundUseCase;

  @Mock private BookingRepository bookingRepository;
  @Mock private EventPublisher eventPublisher;

  private static final String BOOKING_NUMBER = "BOOK-1234";
  private static final Long ADMIN_ID = 99L;
  private static final Long USER_ID = 10L;
  private static final Long SEAT_ID = 3L;
  private static final Long BOOKING_ID = 7L;
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 4, 12, 0);

  @BeforeEach
  void setUp() {
    Clock fixedClock =
        Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    bookingAdminRefundUseCase =
        new BookingAdminRefundUseCase(bookingRepository, eventPublisher, fixedClock);
  }

  private Booking bookingWithStatus(BookingStatus status) {
    Booking booking =
        Booking.builder()
            .userId(USER_ID)
            .performanceId(2L)
            .seatId(SEAT_ID)
            .bookingNumber(BOOKING_NUMBER)
            .bookingStatus(status)
            .build();
    ReflectionTestUtils.setField(booking, "id", BOOKING_ID);
    return booking;
  }

  @Test
  @DisplayName("성공: 확정된 예매를 REFUNDING으로 전환하고 RefundRequestedEvent를 발행한다")
  void execute_success() {
    // given
    Booking booking = bookingWithStatus(BookingStatus.CONFIRMED);
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER)).willReturn(Optional.of(booking));

    // when
    bookingAdminRefundUseCase.execute(ADMIN_ID, BOOKING_NUMBER);

    // then
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.REFUNDING);
    verify(eventPublisher)
        .publish(
            argThat(
                (RefundRequestedEvent event) ->
                    event.bookingId().equals(BOOKING_ID)
                        && event.bookingNumber().equals(BOOKING_NUMBER)
                        && event.seatId().equals(SEAT_ID)
                        && event.userId().equals(USER_ID)
                        && event.requestedAt().equals(NOW)));
  }

  @Test
  @DisplayName("성공: 환불 실패 이력이 있어도 확정 상태면 환불할 수 있다 (재환불 API와 달리 실패 이력을 요구하지 않는다)")
  void execute_success_regardless_of_refund_failure_history() {
    // given: 환불에 한 번 실패해 CONFIRMED로 복원된 예매
    Booking booking = bookingWithStatus(BookingStatus.REFUNDING);
    booking.recordRefundFailure(LocalDateTime.of(2026, 8, 1, 12, 0));
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER)).willReturn(Optional.of(booking));

    // when
    bookingAdminRefundUseCase.execute(ADMIN_ID, BOOKING_NUMBER);

    // then
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.REFUNDING);
    verify(eventPublisher).publish(argThat((RefundRequestedEvent event) -> true));
  }

  @ParameterizedTest
  @EnumSource(
      value = BookingStatus.class,
      names = {"PENDING", "CANCELED", "REFUNDING", "REFUNDED", "EXPIRED"})
  @DisplayName("실패: 확정 상태가 아닌 예매는 BOOKING_409_001로 거절하고 이벤트를 발행하지 않는다")
  void execute_rejects_non_confirmed_booking(BookingStatus status) {
    // given
    Booking booking = bookingWithStatus(status);
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER)).willReturn(Optional.of(booking));

    // when & then
    assertThatThrownBy(() -> bookingAdminRefundUseCase.execute(ADMIN_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", BOOKING_CANCEL_NOT_ALLOWED);

    assertThat(booking.getBookingStatus()).isEqualTo(status);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  @DisplayName("실패: 존재하지 않는 예매는 BOOKING_404_001로 거절한다")
  void execute_rejects_unknown_booking() {
    // given
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> bookingAdminRefundUseCase.execute(ADMIN_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", BOOKING_NOT_FOUND);

    verifyNoInteractions(eventPublisher);
  }
}
