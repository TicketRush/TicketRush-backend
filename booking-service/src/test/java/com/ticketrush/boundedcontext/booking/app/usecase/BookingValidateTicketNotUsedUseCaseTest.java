package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.apiclient.TicketRestClient;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
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

  @InjectMocks private BookingValidateTicketNotUsedUseCase bookingValidateTicketNotUsedUseCase;

  @Mock private BookingRepository bookingRepository;
  @Mock private TicketRestClient ticketRestClient;

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

  private void givenOwnedBooking(BookingStatus status) {
    given(bookingRepository.findByBookingNumberAndUserId(BOOKING_NUMBER, USER_ID))
        .willReturn(Optional.of(bookingWithStatus(status)));
  }

  @Test
  @DisplayName("실패: 입장을 완료한(USED) CONFIRMED 예매는 환불을 거부한다")
  void execute_rejects_used_ticket() {
    // given
    givenOwnedBooking(BookingStatus.CONFIRMED);
    given(ticketRestClient.isTicketUsed(BOOKING_ID)).willReturn(true);

    // when & then
    assertThatThrownBy(() -> bookingValidateTicketNotUsedUseCase.execute(USER_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED);
  }

  @Test
  @DisplayName("성공: 입장하지 않은 CONFIRMED 예매는 통과시킨다")
  void execute_passes_unused_ticket() {
    // given
    givenOwnedBooking(BookingStatus.CONFIRMED);
    given(ticketRestClient.isTicketUsed(BOOKING_ID)).willReturn(false);

    // when & then
    assertThatCode(() -> bookingValidateTicketNotUsedUseCase.execute(USER_ID, BOOKING_NUMBER))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("실패: 소유자가 아니면 BOOKING_NOT_FOUND — 티켓을 조회하지 않아 타인 예매의 입장 여부가 새지 않는다")
  void execute_throws_when_not_owner() {
    // given: 다른 사용자의 예매 번호로 호출하면 소유자 조건이 걸려 조회되지 않는다
    given(bookingRepository.findByBookingNumberAndUserId(BOOKING_NUMBER, USER_ID))
        .willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> bookingValidateTicketNotUsedUseCase.execute(USER_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.BOOKING_NOT_FOUND);

    verifyNoInteractions(ticketRestClient);
  }

  @Test
  @DisplayName("실패: ticket-service 통신 실패는 그대로 전파해 환불을 거부한다(알 수 없으면 막는다)")
  void execute_propagates_communication_failure() {
    // given
    givenOwnedBooking(BookingStatus.CONFIRMED);
    given(ticketRestClient.isTicketUsed(BOOKING_ID))
        .willThrow(new BusinessException(ErrorStatus.BOOKING_TICKET_COMMUNICATION_FAILED));

    // when & then
    assertThatThrownBy(() -> bookingValidateTicketNotUsedUseCase.execute(USER_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_TICKET_COMMUNICATION_FAILED);
  }

  @Test
  @DisplayName("성공: CONFIRMED가 아닌 예매(CANCELED)는 환불 개시가 아니므로 티켓을 조회하지 않고 통과시킨다")
  void execute_skips_ticket_lookup_when_not_confirmed() {
    // given: 뒤따르는 취소 유스케이스가 BOOKING_CANCEL_NOT_ALLOWED로 거절할 몫이다
    givenOwnedBooking(BookingStatus.CANCELED);

    // when & then
    assertThatCode(() -> bookingValidateTicketNotUsedUseCase.execute(USER_ID, BOOKING_NUMBER))
        .doesNotThrowAnyException();

    verifyNoInteractions(ticketRestClient);
  }

  @Test
  @DisplayName("성공: REFUNDING 고착 재발행은 입장권이 USED여도 막지 않는다 — 막으면 흡수 상태가 된다 (#397)")
  void executeForAdmin_allows_stuck_refunding_recovery_even_when_used() {
    // given: 이미 환불이 진행 중인(REFUNDING) 예매의 입장권이 USED다
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER))
        .willReturn(Optional.of(bookingWithStatus(BookingStatus.REFUNDING)));
    given(ticketRestClient.isTicketUsed(BOOKING_ID)).willReturn(true);

    // when & then: 차단하지 않는다(좌석 반환 위험은 [CRITICAL] 로그로 가시화한다)
    assertThatCode(() -> bookingValidateTicketNotUsedUseCase.executeForAdmin(BOOKING_NUMBER))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("성공: REFUNDING 건의 입장권 조회가 실패해도 고착 복구를 막지 않는다")
  void executeForAdmin_swallows_lookup_failure_for_refunding() {
    // given
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER))
        .willReturn(Optional.of(bookingWithStatus(BookingStatus.REFUNDING)));
    given(ticketRestClient.isTicketUsed(BOOKING_ID))
        .willThrow(new BusinessException(ErrorStatus.BOOKING_TICKET_COMMUNICATION_FAILED));

    // when & then: 가시화 실패가 복구를 막아선 안 된다
    assertThatCode(() -> bookingValidateTicketNotUsedUseCase.executeForAdmin(BOOKING_NUMBER))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("실패: 관리자 경로에서도 CONFIRMED 예매가 입장을 완료했다면 재환불을 거부한다")
  void executeForAdmin_rejects_used_ticket_on_confirmed() {
    // given
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER))
        .willReturn(Optional.of(bookingWithStatus(BookingStatus.CONFIRMED)));
    given(ticketRestClient.isTicketUsed(BOOKING_ID)).willReturn(true);

    // when & then
    assertThatThrownBy(() -> bookingValidateTicketNotUsedUseCase.executeForAdmin(BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED);

    verify(bookingRepository, never()).findByBookingNumberAndUserId(BOOKING_NUMBER, USER_ID);
  }

  @Test
  @DisplayName("실패: 관리자 경로에서 예매가 없으면 BOOKING_NOT_FOUND")
  void executeForAdmin_throws_when_booking_not_found() {
    // given
    given(bookingRepository.findByBookingNumber(BOOKING_NUMBER)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> bookingValidateTicketNotUsedUseCase.executeForAdmin(BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.BOOKING_NOT_FOUND);

    verifyNoInteractions(ticketRestClient);
  }
}
