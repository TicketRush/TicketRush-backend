package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.policy.RefundingStuckPolicy;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.apiclient.TicketRestClient;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BookingValidateTicketNotUsedUseCaseTest {

  private static final Long USER_ID = 10L;
  private static final Long BOOKING_ID = 100L;
  private static final String BOOKING_NUMBER = "BOOK-1234";
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 13, 12, 0);
  private static final LocalDateTime CUTOFF = NOW.minusMinutes(30);

  @InjectMocks private BookingValidateTicketNotUsedUseCase bookingValidateTicketNotUsedUseCase;

  @Mock private BookingRepository bookingRepository;
  @Mock private TicketRestClient ticketRestClient;
  @Mock private RefundingStuckPolicy refundingStuckPolicy;

  private Booking bookingWithStatus(BookingStatus status) {
    Booking booking =
        Booking.builder()
            .bookingNumber(BOOKING_NUMBER)
            .userId(USER_ID)
            .performanceId(2L)
            .seatId(3L)
            .bookingStatus(status)
            .build();
    ReflectionTestUtils.setField(booking, "id", BOOKING_ID); // AutoIdBaseEntity의 ID 강제 주입
    return booking;
  }

  /** 고착 판정은 updatedAt이 cutoff보다 이전일 때 성립한다(Booking#isStuckInRefunding). */
  private Booking stuckRefundingBooking() {
    Booking booking = bookingWithStatus(BookingStatus.REFUNDING);
    ReflectionTestUtils.setField(booking, "updatedAt", CUTOFF.minusMinutes(1));
    return booking;
  }

  private Booking freshRefundingBooking() {
    Booking booking = bookingWithStatus(BookingStatus.REFUNDING);
    ReflectionTestUtils.setField(booking, "updatedAt", NOW);
    return booking;
  }

  private void givenOwned(Booking booking) {
    given(bookingRepository.findByBookingNumberAndUserId(BOOKING_NUMBER, USER_ID))
        .willReturn(Optional.of(booking));
  }

  private void givenFound(Booking booking) {
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER)).willReturn(Optional.of(booking));
  }

  private void givenCutoff() {
    given(refundingStuckPolicy.cutoff()).willReturn(CUTOFF);
  }

  // ---------- 사용자 취소 경로 ----------

  @Test
  @DisplayName("실패: 입장을 완료한(USED) CONFIRMED 예매는 환불을 거부한다")
  void execute_rejects_used_ticket() {
    givenOwned(bookingWithStatus(BookingStatus.CONFIRMED));
    given(ticketRestClient.isTicketUsed(BOOKING_ID)).willReturn(true);

    assertThatThrownBy(() -> bookingValidateTicketNotUsedUseCase.execute(USER_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED);
  }

  @Test
  @DisplayName("성공: 입장하지 않은 CONFIRMED 예매는 통과시킨다")
  void execute_passes_unused_ticket() {
    givenOwned(bookingWithStatus(BookingStatus.CONFIRMED));
    given(ticketRestClient.isTicketUsed(BOOKING_ID)).willReturn(false);

    assertThatCode(() -> bookingValidateTicketNotUsedUseCase.execute(USER_ID, BOOKING_NUMBER))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("실패: REFUNDING 예매도 검사한다 — 취소 유스케이스가 읽기 전에 CONFIRMED로 복원될 수 있다(#391)")
  void execute_also_checks_refunding_to_close_restore_window() {
    // given: 가드가 REFUNDING을 그냥 통과시키면, 그 사이 recordRefundFailure로 CONFIRMED가 복원됐을 때
    // 취소 유스케이스는 티켓 검사를 한 번도 받지 않은 USED 예매의 환불을 개시하게 된다.
    givenOwned(bookingWithStatus(BookingStatus.REFUNDING));
    given(ticketRestClient.isTicketUsed(BOOKING_ID)).willReturn(true);

    assertThatThrownBy(() -> bookingValidateTicketNotUsedUseCase.execute(USER_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED);
  }

  @Test
  @DisplayName("실패: 소유자가 아니면 BOOKING_NOT_FOUND — 티켓을 조회하지 않아 타인 예매의 입장 여부가 새지 않는다")
  void execute_throws_when_not_owner() {
    given(bookingRepository.findByBookingNumberAndUserId(BOOKING_NUMBER, USER_ID))
        .willReturn(Optional.empty());

    assertThatThrownBy(() -> bookingValidateTicketNotUsedUseCase.execute(USER_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.BOOKING_NOT_FOUND);

    verifyNoInteractions(ticketRestClient);
  }

  @Test
  @DisplayName("실패: ticket-service 통신 실패는 그대로 전파해 환불을 거부한다(알 수 없으면 막는다)")
  void execute_propagates_communication_failure() {
    givenOwned(bookingWithStatus(BookingStatus.CONFIRMED));
    given(ticketRestClient.isTicketUsed(BOOKING_ID))
        .willThrow(new BusinessException(ErrorStatus.BOOKING_TICKET_COMMUNICATION_FAILED));

    assertThatThrownBy(() -> bookingValidateTicketNotUsedUseCase.execute(USER_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_TICKET_COMMUNICATION_FAILED);
  }

  @Test
  @DisplayName("성공: CANCELED 예매는 CONFIRMED로 돌아올 수 없으므로 티켓을 조회하지 않는다")
  void execute_skips_ticket_lookup_when_not_refundable() {
    // given: 뒤따르는 취소 유스케이스가 BOOKING_CANCEL_NOT_ALLOWED로 거절할 몫이다
    givenOwned(bookingWithStatus(BookingStatus.CANCELED));

    assertThatCode(() -> bookingValidateTicketNotUsedUseCase.execute(USER_ID, BOOKING_NUMBER))
        .doesNotThrowAnyException();

    verifyNoInteractions(ticketRestClient);
  }

  // ---------- 관리자 재환불 경로 ----------

  @Test
  @DisplayName("성공: REFUNDING 고착 복구는 입장권이 USED여도 막지 않는다 — 막으면 흡수 상태가 된다 (#397)")
  void executeForAdmin_allows_stuck_recovery_even_when_used() {
    givenFound(stuckRefundingBooking());
    givenCutoff();
    given(ticketRestClient.isTicketUsed(BOOKING_ID)).willReturn(true);

    // 차단하지 않는다(좌석 반환 위험은 [CRITICAL] 로그로 가시화한다)
    assertThatCode(() -> bookingValidateTicketNotUsedUseCase.executeForAdmin(BOOKING_NUMBER))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("성공: 고착 복구는 입장권 조회가 어떤 예외로 실패해도 막지 않는다")
  void executeForAdmin_swallows_any_lookup_failure_for_stuck_recovery() {
    // given: BusinessException이 아닌 예상 밖 런타임 예외도 복구를 막아선 안 된다
    givenFound(stuckRefundingBooking());
    givenCutoff();
    given(ticketRestClient.isTicketUsed(BOOKING_ID))
        .willThrow(new IllegalStateException("예상 밖 오류"));

    assertThatCode(() -> bookingValidateTicketNotUsedUseCase.executeForAdmin(BOOKING_NUMBER))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("실패: 고착이 아닌(신선한) REFUNDING의 재환불은 입장권이 USED면 거부한다")
  void executeForAdmin_rejects_used_ticket_on_fresh_refunding() {
    givenFound(freshRefundingBooking());
    givenCutoff();
    given(ticketRestClient.isTicketUsed(BOOKING_ID)).willReturn(true);

    assertThatThrownBy(() -> bookingValidateTicketNotUsedUseCase.executeForAdmin(BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED);
  }

  @Test
  @DisplayName("실패: 관리자 경로에서도 CONFIRMED 예매가 입장을 완료했다면 재환불을 거부한다")
  void executeForAdmin_rejects_used_ticket_on_confirmed() {
    givenFound(bookingWithStatus(BookingStatus.CONFIRMED));
    givenCutoff();
    given(ticketRestClient.isTicketUsed(BOOKING_ID)).willReturn(true);

    assertThatThrownBy(() -> bookingValidateTicketNotUsedUseCase.executeForAdmin(BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED);
  }

  @Test
  @DisplayName("실패: 관리자 경로에서 예매가 없으면 BOOKING_NOT_FOUND")
  void executeForAdmin_throws_when_booking_not_found() {
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER)).willReturn(Optional.empty());

    assertThatThrownBy(() -> bookingValidateTicketNotUsedUseCase.executeForAdmin(BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.BOOKING_NOT_FOUND);

    verifyNoInteractions(ticketRestClient);
  }
}
