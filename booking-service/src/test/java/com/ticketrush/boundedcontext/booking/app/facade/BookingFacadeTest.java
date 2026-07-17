package com.ticketrush.boundedcontext.booking.app.facade;

import static com.ticketrush.global.status.ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED;
import static com.ticketrush.global.status.ErrorStatus.SEAT_ALREADY_LOCKED;
import static com.ticketrush.global.status.ErrorStatus.USER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.booking.app.dto.request.BookingCreateRequest;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingCountResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPendingResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingAdminRetryRefundUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCancelMyBookingUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCountUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCreateUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetMyBookingsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetRefundingStuckBookingsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingIssueNumberUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateReferencesUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateSeatAvailableUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateTicketNotUsedUseCase;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import com.ticketrush.global.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

@ExtendWith(MockitoExtension.class)
class BookingFacadeTest {

  @InjectMocks private BookingFacade bookingFacade;

  @Mock private BookingIssueNumberUseCase bookingIssueNumberUseCase;
  @Mock private BookingCreateUseCase bookingCreateUseCase;
  @Mock private BookingGetMyBookingsUseCase bookingGetMyBookingsUseCase;
  @Mock private BookingCountUseCase bookingCountUseCase;
  @Mock private BookingCancelMyBookingUseCase bookingCancelMyBookingUseCase;
  @Mock private BookingValidateReferencesUseCase bookingValidateReferencesUseCase;
  @Mock private BookingValidateSeatAvailableUseCase bookingValidateSeatAvailableUseCase;
  @Mock private BookingGetRefundingStuckBookingsUseCase bookingGetRefundingStuckBookingsUseCase;
  @Mock private BookingValidateTicketNotUsedUseCase bookingValidateTicketNotUsedUseCase;
  @Mock private BookingAdminRetryRefundUseCase bookingAdminRetryRefundUseCase;

  @Test
  @DisplayName("성공: 참조 검증 후 예약번호를 발급하고 예매를 생성한다")
  void createBooking_success() {
    // given
    Long userId = 1L;
    Long performanceId = 2L;
    Long seatId = 3L;
    String bookingNumber = "BOOK-1234";
    Booking booking =
        Booking.builder()
            .userId(userId)
            .performanceId(performanceId)
            .seatId(seatId)
            .bookingNumber(bookingNumber)
            .bookingStatus(BookingStatus.PENDING)
            .build();

    given(bookingIssueNumberUseCase.execute()).willReturn(bookingNumber);
    given(
            bookingCreateUseCase.execute(
                new BookingCreateRequest(userId, performanceId, seatId, bookingNumber)))
        .willReturn(booking);

    // when
    BookingPendingResponse result = bookingFacade.createBooking(userId, performanceId, seatId);

    // then
    assertThat(result.bookingNumber()).isEqualTo(bookingNumber);
    assertThat(result.status()).isEqualTo(BookingStatus.PENDING.name());
    verify(bookingValidateReferencesUseCase).execute(userId, performanceId, seatId);
    verify(bookingValidateSeatAvailableUseCase).execute(seatId, performanceId);
  }

  @Test
  @DisplayName("실패: 참조 검증에 실패하면 예약번호 발급과 예매 생성을 하지 않는다")
  void createBooking_fail_when_reference_validation_fails() {
    // given
    Long userId = 1L;
    Long performanceId = 2L;
    Long seatId = 3L;

    doThrow(new BusinessException(USER_NOT_FOUND))
        .when(bookingValidateReferencesUseCase)
        .execute(userId, performanceId, seatId);

    // when & then
    assertThatThrownBy(() -> bookingFacade.createBooking(userId, performanceId, seatId))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(USER_NOT_FOUND);

    verifyNoInteractions(bookingValidateSeatAvailableUseCase);
    verifyNoInteractions(bookingIssueNumberUseCase, bookingCreateUseCase);
  }

  @Test
  @DisplayName("실패: 좌석 HOLD 검증에 실패하면 예약번호 발급과 예매 생성을 하지 않는다")
  void createBooking_fail_when_seat_is_held() {
    // given
    Long userId = 1L;
    Long performanceId = 2L;
    Long seatId = 3L;

    doThrow(new BusinessException(SEAT_ALREADY_LOCKED))
        .when(bookingValidateSeatAvailableUseCase)
        .execute(seatId, performanceId);

    // when & then
    assertThatThrownBy(() -> bookingFacade.createBooking(userId, performanceId, seatId))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(SEAT_ALREADY_LOCKED);

    verify(bookingValidateReferencesUseCase).execute(userId, performanceId, seatId);
    verify(bookingValidateSeatAvailableUseCase).execute(seatId, performanceId);
    verifyNoInteractions(bookingIssueNumberUseCase, bookingCreateUseCase);
  }

  @Test
  @DisplayName("성공: 회원 예매 내역 조회를 위임한다")
  void getMyBookings_success() {
    // given
    Long userId = 1L;
    BookingSummaryResponse response =
        new BookingSummaryResponse(
            100L,
            "BOOK-1234",
            userId,
            2L,
            3L,
            BookingStatus.CONFIRMED,
            LocalDateTime.of(2026, 5, 22, 10, 30),
            null,
            null);

    given(
            bookingGetMyBookingsUseCase.execute(
                userId, BookingStatus.CONFIRMED, new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(response)));

    // when
    Page<BookingSummaryResponse> result =
        bookingFacade.getMyBookings(userId, BookingStatus.CONFIRMED, new OffsetPageRequest(0, 10));

    // then
    assertThat(result.getContent()).containsExactly(response);
    verify(bookingGetMyBookingsUseCase)
        .execute(userId, BookingStatus.CONFIRMED, new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("성공: 회원 예매 수 조회를 위임한다")
  void countMyBookings_success() {
    // given
    Long userId = 1L;
    BookingCountResponse response = new BookingCountResponse(BookingStatus.CONFIRMED, 3L);
    given(bookingCountUseCase.execute(userId, BookingStatus.CONFIRMED)).willReturn(response);

    // when
    BookingCountResponse result = bookingFacade.countMyBookings(userId, BookingStatus.CONFIRMED);

    // then
    assertThat(result).isEqualTo(response);
    verify(bookingCountUseCase).execute(userId, BookingStatus.CONFIRMED);
  }

  @Test
  @DisplayName("성공: 환불 고착 예매 조회를 위임한다")
  void getRefundingStuckBookings_success() {
    // given
    BookingSummaryResponse response =
        new BookingSummaryResponse(
            100L,
            "BOOK-1234",
            1L,
            2L,
            3L,
            BookingStatus.REFUNDING,
            LocalDateTime.of(2026, 5, 22, 10, 30),
            null,
            LocalDateTime.of(2026, 7, 13, 11, 0));

    given(bookingGetRefundingStuckBookingsUseCase.execute(new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(response)));

    // when
    Page<BookingSummaryResponse> result =
        bookingFacade.getRefundingStuckBookings(new OffsetPageRequest(0, 10));

    // then
    assertThat(result.getContent()).containsExactly(response);
    verify(bookingGetRefundingStuckBookingsUseCase).execute(new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("성공: 입장권 사용 여부를 검증한 뒤 회원 예매 취소를 위임한다")
  void cancelMyBooking_success() {
    // given
    Long userId = 1L;
    String bookingNumber = "BOOK-1234";

    // when
    bookingFacade.cancelMyBooking(userId, bookingNumber);

    // then: 검증이 취소보다 먼저 수행돼야 한다 (#399). 소유권도 함께 검증하도록 userId를 넘긴다.
    InOrder inOrder = inOrder(bookingValidateTicketNotUsedUseCase, bookingCancelMyBookingUseCase);
    inOrder.verify(bookingValidateTicketNotUsedUseCase).execute(userId, bookingNumber);
    inOrder.verify(bookingCancelMyBookingUseCase).execute(userId, bookingNumber);
  }

  @Test
  @DisplayName("실패: 입장을 완료한 예매면 취소 유스케이스를 호출하지 않는다 (#399)")
  void cancelMyBooking_rejects_used_ticket_before_refund() {
    // given
    Long userId = 1L;
    String bookingNumber = "BOOK-1234";
    doThrow(new BusinessException(BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED))
        .when(bookingValidateTicketNotUsedUseCase)
        .execute(userId, bookingNumber);

    // when & then
    assertThatThrownBy(() -> bookingFacade.cancelMyBooking(userId, bookingNumber))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED);

    verifyNoInteractions(bookingCancelMyBookingUseCase);
  }

  @Test
  @DisplayName("성공: 입장권 사용 여부를 검증한 뒤 관리자 재환불을 위임한다")
  void retryRefund_success() {
    // given
    Long adminId = 99L;
    String bookingNumber = "BOOK-1234";

    // when
    bookingFacade.retryRefund(adminId, bookingNumber);

    // then: 검증이 재환불보다 먼저 수행돼야 한다 (#399). 관리자는 소유권이 없으므로 전용 진입점을 쓴다.
    InOrder inOrder = inOrder(bookingValidateTicketNotUsedUseCase, bookingAdminRetryRefundUseCase);
    inOrder.verify(bookingValidateTicketNotUsedUseCase).executeForAdmin(bookingNumber);
    inOrder.verify(bookingAdminRetryRefundUseCase).execute(adminId, bookingNumber);
  }

  @Test
  @DisplayName("실패: 입장을 완료한 예매면 관리자 재환불도 차단한다 (#399)")
  void retryRefund_rejects_used_ticket_before_refund() {
    // given
    Long adminId = 99L;
    String bookingNumber = "BOOK-1234";
    doThrow(new BusinessException(BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED))
        .when(bookingValidateTicketNotUsedUseCase)
        .executeForAdmin(bookingNumber);

    // when & then
    assertThatThrownBy(() -> bookingFacade.retryRefund(adminId, bookingNumber))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED);

    verifyNoInteractions(bookingAdminRetryRefundUseCase);
  }
}
