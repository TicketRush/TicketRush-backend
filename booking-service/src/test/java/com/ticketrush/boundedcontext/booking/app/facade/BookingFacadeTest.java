package com.ticketrush.boundedcontext.booking.app.facade;

import static com.ticketrush.global.status.ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED;
import static com.ticketrush.global.status.ErrorStatus.BOOKING_REFUND_DEADLINE_PASSED;
import static com.ticketrush.global.status.ErrorStatus.SEAT_ALREADY_LOCKED;
import static com.ticketrush.global.status.ErrorStatus.USER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.booking.app.dto.request.BookingCreateRequest;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminRefundSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingCountResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingDetailResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingMySummaryResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPendingResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingRefundStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingAdminRefundUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingAdminRetryRefundUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCancelMyBookingUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCountUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCreateUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetAdminBookingUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetAdminBookingsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetAdminRefundStatsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetAdminRefundsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetAdminStatsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetMyBookingDetailUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetMyBookingsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetRefundingStuckBookingsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingIssueNumberUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateReferencesUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateRefundDeadlineUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateSeatAvailableUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateTicketNotUsedUseCase;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.domain.types.RefundProcessStatus;
import com.ticketrush.boundedcontext.booking.out.apiclient.PerformanceRestClient;
import com.ticketrush.boundedcontext.booking.out.apiclient.SeatRestClient;
import com.ticketrush.boundedcontext.booking.out.apiclient.UserRestClient;
import com.ticketrush.boundedcontext.booking.out.apiclient.dto.PerformanceInfoResponse;
import com.ticketrush.boundedcontext.booking.out.apiclient.dto.UserSummaryInfoResponse;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import com.ticketrush.global.exception.BusinessException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  @Mock private BookingGetMyBookingDetailUseCase bookingGetMyBookingDetailUseCase;
  @Mock private BookingGetMyBookingsUseCase bookingGetMyBookingsUseCase;
  @Mock private PerformanceRestClient performanceRestClient;
  @Mock private BookingCountUseCase bookingCountUseCase;
  @Mock private BookingCancelMyBookingUseCase bookingCancelMyBookingUseCase;
  @Mock private BookingValidateReferencesUseCase bookingValidateReferencesUseCase;
  @Mock private BookingValidateSeatAvailableUseCase bookingValidateSeatAvailableUseCase;
  @Mock private BookingGetRefundingStuckBookingsUseCase bookingGetRefundingStuckBookingsUseCase;
  @Mock private BookingValidateTicketNotUsedUseCase bookingValidateTicketNotUsedUseCase;
  @Mock private BookingValidateRefundDeadlineUseCase bookingValidateRefundDeadlineUseCase;
  @Mock private BookingAdminRetryRefundUseCase bookingAdminRetryRefundUseCase;
  @Mock private SeatRestClient seatRestClient;
  @Mock private BookingGetAdminBookingsUseCase bookingGetAdminBookingsUseCase;
  @Mock private BookingGetAdminBookingUseCase bookingGetAdminBookingUseCase;
  @Mock private BookingGetAdminStatsUseCase bookingGetAdminStatsUseCase;
  @Mock private BookingGetAdminRefundsUseCase bookingGetAdminRefundsUseCase;
  @Mock private BookingGetAdminRefundStatsUseCase bookingGetAdminRefundStatsUseCase;
  @Mock private BookingAdminRefundUseCase bookingAdminRefundUseCase;
  @Mock private UserRestClient userRestClient;

  private static Booking myBooking(Long userId, String bookingNumber) {
    return Booking.builder()
        .userId(userId)
        .performanceId(2L)
        .seatId(3L)
        .bookingNumber(bookingNumber)
        .bookingStatus(BookingStatus.CONFIRMED)
        .build();
  }

  private static PerformanceInfoResponse performanceInfo() {
    return new PerformanceInfoResponse(
        "오페라의 유령", LocalDate.of(2026, 5, 22), LocalTime.of(19, 30), "서울 예술의전당 오페라극장", 150000L);
  }

  @Test
  @DisplayName("성공: 단건 조회는 공연·좌석을 보강해 모든 필드를 채운다")
  void getMyBooking_enriches_all_fields() {
    // given
    Long userId = 1L;
    String bookingNumber = "X7B29-KLPW1";
    given(bookingGetMyBookingDetailUseCase.execute(userId, bookingNumber))
        .willReturn(myBooking(userId, bookingNumber));
    given(performanceRestClient.getPerformance(2L)).willReturn(Optional.of(performanceInfo()));
    given(seatRestClient.getSeatNumbers(List.of(3L))).willReturn(Map.of(3L, "A-1"));

    // when
    BookingDetailResponse response = bookingFacade.getMyBooking(userId, bookingNumber);

    // then
    assertThat(response.bookingNumber()).isEqualTo(bookingNumber);
    assertThat(response.performanceTitle()).isEqualTo("오페라의 유령");
    assertThat(response.performanceDate()).isEqualTo(LocalDate.of(2026, 5, 22));
    assertThat(response.performanceTime()).isEqualTo(LocalTime.of(19, 30));
    assertThat(response.performanceAddress()).isEqualTo("서울 예술의전당 오페라극장");
    assertThat(response.paymentAmount()).isEqualTo(150000L);
    assertThat(response.seatNumber()).isEqualTo("A-1");
  }

  @Test
  @DisplayName("부분 응답: 공연 조회 실패는 공연 필드·결제 금액만 비우고 좌석 번호는 남긴다 — 실패 격리")
  void getMyBooking_isolates_performance_failure() {
    // given
    Long userId = 1L;
    String bookingNumber = "X7B29-KLPW1";
    given(bookingGetMyBookingDetailUseCase.execute(userId, bookingNumber))
        .willReturn(myBooking(userId, bookingNumber));
    given(performanceRestClient.getPerformance(2L)).willReturn(Optional.empty());
    given(seatRestClient.getSeatNumbers(List.of(3L))).willReturn(Map.of(3L, "A-1"));

    // when
    BookingDetailResponse response = bookingFacade.getMyBooking(userId, bookingNumber);

    // then
    assertThat(response.performanceTitle()).isNull();
    assertThat(response.paymentAmount()).isNull();
    assertThat(response.performanceId()).isEqualTo(2L); // 프론트 재조회 키는 유지
    assertThat(response.seatNumber()).isEqualTo("A-1");
    assertThat(response.bookingNumber()).isEqualTo(bookingNumber);
  }

  @Test
  @DisplayName("부분 응답: 좌석 조회 실패는 좌석 번호만 비운다")
  void getMyBooking_isolates_seat_failure() {
    // given
    Long userId = 1L;
    String bookingNumber = "X7B29-KLPW1";
    given(bookingGetMyBookingDetailUseCase.execute(userId, bookingNumber))
        .willReturn(myBooking(userId, bookingNumber));
    given(performanceRestClient.getPerformance(2L)).willReturn(Optional.of(performanceInfo()));
    given(seatRestClient.getSeatNumbers(List.of(3L))).willReturn(Map.of());

    // when
    BookingDetailResponse response = bookingFacade.getMyBooking(userId, bookingNumber);

    // then
    assertThat(response.seatNumber()).isNull();
    assertThat(response.seatId()).isEqualTo(3L); // 프론트 재조회 키는 유지
    assertThat(response.performanceTitle()).isEqualTo("오페라의 유령");
  }

  @Test
  @DisplayName("부분 응답: 공연·좌석이 동시에 실패해도 booking 코어 필드는 전부 살아남는다")
  void getMyBooking_keeps_core_fields_when_all_enrichment_fails() {
    // given
    Long userId = 1L;
    String bookingNumber = "X7B29-KLPW1";
    given(bookingGetMyBookingDetailUseCase.execute(userId, bookingNumber))
        .willReturn(myBooking(userId, bookingNumber));
    given(performanceRestClient.getPerformance(2L)).willReturn(Optional.empty());
    given(seatRestClient.getSeatNumbers(List.of(3L))).willReturn(Map.of());

    // when
    BookingDetailResponse response = bookingFacade.getMyBooking(userId, bookingNumber);

    // then — 보강 필드는 모두 비지만 코어와 재조회 키는 남는다
    assertThat(response.performanceTitle()).isNull();
    assertThat(response.seatNumber()).isNull();
    assertThat(response.paymentAmount()).isNull();
    assertThat(response.bookingNumber()).isEqualTo(bookingNumber);
    assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(response.performanceId()).isEqualTo(2L);
    assertThat(response.seatId()).isEqualTo(3L);
  }

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

  private static BookingSummaryResponse summary(Long bookingId, Long performanceId, Long seatId) {
    return new BookingSummaryResponse(
        bookingId,
        "BOOK-" + bookingId,
        1L,
        performanceId,
        seatId,
        BookingStatus.CONFIRMED,
        LocalDateTime.of(2026, 5, 22, 10, 30),
        null,
        null,
        null);
  }

  @Test
  @DisplayName("성공: 회원 예매 내역을 공연·좌석으로 보강한다 — 같은 공연은 distinct로 묶어 한 번만 조회한다")
  void getMyBookings_enriches_with_distinct_performance_ids() {
    // given
    Long userId = 1L;
    List<BookingSummaryResponse> summaries =
        List.of(summary(100L, 2L, 3L), summary(101L, 2L, 4L), summary(102L, 2L, 5L));

    given(
            bookingGetMyBookingsUseCase.execute(
                userId, BookingStatus.CONFIRMED, new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(summaries));
    given(performanceRestClient.getPerformances(Set.of(2L)))
        .willReturn(Map.of(2L, performanceInfo()));
    given(seatRestClient.getSeatNumbers(List.of(3L, 4L, 5L)))
        .willReturn(Map.of(3L, "A-1", 4L, "A-2", 5L, "A-3"));

    // when
    Page<BookingMySummaryResponse> result =
        bookingFacade.getMyBookings(userId, BookingStatus.CONFIRMED, new OffsetPageRequest(0, 10));

    // then — 같은 공연 3건이 distinct 1개로 묶여 전달됐는지는 위 given의 Set.of(2L) 매칭이 검증한다
    assertThat(result.getContent()).hasSize(3);
    assertThat(result.getContent().get(0).performanceTitle()).isEqualTo("오페라의 유령");
    assertThat(result.getContent().get(0).performanceDate()).isEqualTo(LocalDate.of(2026, 5, 22));
    assertThat(result.getContent().get(0).performanceAddress()).isEqualTo("서울 예술의전당 오페라극장");
    assertThat(result.getContent().get(0).paymentAmount()).isEqualTo(150000L);
    assertThat(result.getContent().get(0).seatNumber()).isEqualTo("A-1");
    assertThat(result.getContent().get(1).seatNumber()).isEqualTo("A-2");
    assertThat(result.getContent().get(2).seatNumber()).isEqualTo("A-3");
    verify(performanceRestClient).getPerformances(Set.of(2L));
    verify(seatRestClient).getSeatNumbers(List.of(3L, 4L, 5L));
  }

  @Test
  @DisplayName("부분 응답: 벌크 조회에서 누락된 공연만 해당 건의 공연 필드가 비고 나머지는 채워진다")
  void getMyBookings_fills_only_resolved_performances() {
    // given
    Long userId = 1L;
    List<BookingSummaryResponse> summaries = List.of(summary(100L, 2L, 3L), summary(101L, 7L, 4L));

    given(
            bookingGetMyBookingsUseCase.execute(
                userId, BookingStatus.CONFIRMED, new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(summaries));
    // 공연 7은 조회 실패(fail-fast 중단 등)로 맵에서 빠졌다
    given(performanceRestClient.getPerformances(Set.of(2L, 7L)))
        .willReturn(Map.of(2L, performanceInfo()));
    given(seatRestClient.getSeatNumbers(List.of(3L, 4L))).willReturn(Map.of(3L, "A-1", 4L, "A-2"));

    // when
    Page<BookingMySummaryResponse> result =
        bookingFacade.getMyBookings(userId, BookingStatus.CONFIRMED, new OffsetPageRequest(0, 10));

    // then
    assertThat(result.getContent().get(0).performanceTitle()).isEqualTo("오페라의 유령");
    assertThat(result.getContent().get(1).performanceTitle()).isNull();
    assertThat(result.getContent().get(1).performanceId()).isEqualTo(7L); // 프론트 재조회 키는 유지
    assertThat(result.getContent().get(1).seatNumber()).isEqualTo("A-2"); // 좌석은 실패 격리로 생존
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
            LocalDateTime.of(2026, 7, 13, 11, 0),
            null);

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
    InOrder inOrder =
        inOrder(
            bookingValidateTicketNotUsedUseCase,
            bookingValidateRefundDeadlineUseCase,
            bookingCancelMyBookingUseCase);
    inOrder.verify(bookingValidateTicketNotUsedUseCase).execute(userId, bookingNumber);
    inOrder.verify(bookingValidateRefundDeadlineUseCase).execute(userId, bookingNumber);
    inOrder.verify(bookingCancelMyBookingUseCase).execute(userId, bookingNumber);

    // PENDING이 아니었으므로 좌석 즉시 반납은 일어나지 않는다 (#559).
    verifyNoInteractions(seatRestClient);
  }

  @Test
  @DisplayName("성공: PENDING 즉시 취소로 좌석 ID가 돌아오면 seat-service에 반납을 요청한다 (#559)")
  void cancelMyBooking_pending_releases_seat() {
    // given
    Long userId = 1L;
    String bookingNumber = "BOOK-1234";
    Long seatId = 3L;
    given(bookingCancelMyBookingUseCase.execute(userId, bookingNumber))
        .willReturn(java.util.Optional.of(seatId));

    // when
    bookingFacade.cancelMyBooking(userId, bookingNumber);

    // then: 좌석 반납은 취소 커밋 '이후'여야 한다. seat가 되쏘는 SeatHoldExpiredEvent가 도착해도
    // 이미 CANCELED라 EXPIRED로 뒤집히지 않게 하는 순서다.
    InOrder inOrder = inOrder(bookingCancelMyBookingUseCase, seatRestClient);
    inOrder.verify(bookingCancelMyBookingUseCase).execute(userId, bookingNumber);
    inOrder.verify(seatRestClient).releaseHold(bookingNumber, seatId);
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
  @DisplayName("실패: 환불 마감이 지난 예매면 취소 유스케이스를 호출하지 않는다 (#668)")
  void cancelMyBooking_rejects_after_refund_deadline() {
    // given
    Long userId = 1L;
    String bookingNumber = "BOOK-1234";
    doThrow(new BusinessException(BOOKING_REFUND_DEADLINE_PASSED))
        .when(bookingValidateRefundDeadlineUseCase)
        .execute(userId, bookingNumber);

    // when & then
    assertThatThrownBy(() -> bookingFacade.cancelMyBooking(userId, bookingNumber))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", BOOKING_REFUND_DEADLINE_PASSED);

    // 가드가 막았으면 상태 전이도 좌석 반납도 일어나선 안 된다.
    verifyNoInteractions(bookingCancelMyBookingUseCase, seatRestClient);
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

    // D-7은 절대 걸지 않는다 (#668). 이 경로는 REFUNDING 고착(#397) 복구를 겸하고, 고착은 공연이 지난 뒤
    // 발견되는 경우가 주 대상이라 마감을 걸면 정확히 그 건들이 복구 불가가 된다.
    verifyNoInteractions(bookingValidateRefundDeadlineUseCase);
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

  private static final Long ADMIN_ID = 99L;

  private static Booking adminBooking(Long userId, Long performanceId, Long seatId) {
    return Booking.builder()
        .userId(userId)
        .performanceId(performanceId)
        .seatId(seatId)
        .bookingNumber("BK-" + userId)
        .bookingStatus(BookingStatus.CONFIRMED)
        .build();
  }

  /** 결제 완료 경로를 태워 확정한다 — 금액은 공연이 아니라 예매가 보유하므로 confirm()으로만 채워진다 (#561). */
  private static Booking confirmedAdminBooking(Long userId, Long performanceId, Long seatId) {
    Booking booking =
        Booking.builder()
            .userId(userId)
            .performanceId(performanceId)
            .seatId(seatId)
            .bookingNumber("BK-" + userId)
            .bookingStatus(BookingStatus.PENDING)
            .build();
    booking.confirm(LocalDateTime.of(2026, 5, 22, 10, 30), 150000L);
    return booking;
  }

  @Test
  @DisplayName("성공: 관리자 목록은 공연·좌석·예매자를 보강해 모든 필드를 채운다")
  void getAdminBookings_enriches_all_three_axes() {
    // given
    OffsetPageRequest pageRequest = new OffsetPageRequest(0, 10);
    Booking booking = confirmedAdminBooking(5L, 2L, 3L);
    given(bookingGetAdminBookingsUseCase.execute(Set.of(), pageRequest))
        .willReturn(new PageImpl<>(List.of(booking)));
    given(performanceRestClient.getPerformances(Set.of(2L)))
        .willReturn(Map.of(2L, performanceInfo()));
    given(seatRestClient.getSeatNumbers(List.of(3L))).willReturn(Map.of(3L, "A-1"));
    given(userRestClient.getUsers(List.of(5L)))
        .willReturn(Map.of(5L, new UserSummaryInfoResponse(5L, "김소희", "user@example.com")));

    // when
    Page<BookingAdminSummaryResponse> page =
        bookingFacade.getAdminBookings(ADMIN_ID, Set.of(), pageRequest);

    // then
    BookingAdminSummaryResponse response = page.getContent().get(0);
    assertThat(response.performanceTitle()).isEqualTo("오페라의 유령");
    assertThat(response.performanceDate()).isEqualTo(LocalDate.of(2026, 5, 22));
    assertThat(response.seatNumber()).isEqualTo("A-1");
    assertThat(response.bookerName()).isEqualTo("김소희");
    assertThat(response.bookerEmail()).isEqualTo("user@example.com");
    assertThat(response.paymentAmount()).isEqualTo(150000L);
    assertThat(response.seatCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("성공: 공연 조회가 실패해도 결제 금액은 살아남는다 — 금액은 예매가 보유한다")
  void getAdminBookings_keeps_amount_when_performance_lookup_fails() {
    // given: performance-service가 죽어 공연 필드를 못 채우는 상황 (#561)
    OffsetPageRequest pageRequest = new OffsetPageRequest(0, 10);
    given(bookingGetAdminBookingsUseCase.execute(Set.of(), pageRequest))
        .willReturn(new PageImpl<>(List.of(confirmedAdminBooking(5L, 2L, 3L))));
    given(performanceRestClient.getPerformances(Set.of(2L))).willReturn(Map.of());
    given(seatRestClient.getSeatNumbers(List.of(3L))).willReturn(Map.of());
    given(userRestClient.getUsers(List.of(5L))).willReturn(Map.of());

    // when
    Page<BookingAdminSummaryResponse> page =
        bookingFacade.getAdminBookings(ADMIN_ID, Set.of(), pageRequest);

    // then: 공연 가격을 빌려 쓰던 때는 이 경우 금액도 함께 사라졌다
    BookingAdminSummaryResponse response = page.getContent().get(0);
    assertThat(response.performanceTitle()).isNull();
    assertThat(response.paymentAmount()).isEqualTo(150000L);
  }

  @Test
  @DisplayName("성공: 관리자 목록은 중복 ID를 묶어 각 서비스를 한 번씩만 호출한다")
  void getAdminBookings_deduplicates_ids_per_service() {
    // given: 같은 회원이 같은 공연의 좌석 2개를 예매했다
    OffsetPageRequest pageRequest = new OffsetPageRequest(0, 10);
    given(bookingGetAdminBookingsUseCase.execute(Set.of(), pageRequest))
        .willReturn(new PageImpl<>(List.of(adminBooking(5L, 2L, 3L), adminBooking(5L, 2L, 4L))));
    given(performanceRestClient.getPerformances(Set.of(2L))).willReturn(Map.of());
    given(seatRestClient.getSeatNumbers(List.of(3L, 4L))).willReturn(Map.of());
    given(userRestClient.getUsers(List.of(5L))).willReturn(Map.of());

    // when
    bookingFacade.getAdminBookings(ADMIN_ID, Set.of(), pageRequest);

    // then: 공연 1회, 회원 1회 (좌석만 2건)
    verify(performanceRestClient).getPerformances(Set.of(2L));
    verify(userRestClient).getUsers(List.of(5L));
  }

  @Test
  @DisplayName("성공: 관리자 목록에서 예매자 조회가 실패해도 그 필드만 비고 나머지는 살아 있다")
  void getAdminBookings_isolates_user_failure() {
    // given: user-service만 실패해 빈 맵을 돌려준다
    OffsetPageRequest pageRequest = new OffsetPageRequest(0, 10);
    given(bookingGetAdminBookingsUseCase.execute(Set.of(), pageRequest))
        .willReturn(new PageImpl<>(List.of(adminBooking(5L, 2L, 3L))));
    given(performanceRestClient.getPerformances(Set.of(2L)))
        .willReturn(Map.of(2L, performanceInfo()));
    given(seatRestClient.getSeatNumbers(List.of(3L))).willReturn(Map.of(3L, "A-1"));
    given(userRestClient.getUsers(List.of(5L))).willReturn(Map.of());

    // when
    Page<BookingAdminSummaryResponse> page =
        bookingFacade.getAdminBookings(ADMIN_ID, Set.of(), pageRequest);

    // then
    BookingAdminSummaryResponse response = page.getContent().get(0);
    assertThat(response.bookerName()).isNull();
    assertThat(response.bookerEmail()).isNull();
    assertThat(response.userId()).isEqualTo(5L); // 프론트 재조회 키는 남는다
    assertThat(response.performanceTitle()).isEqualTo("오페라의 유령");
    assertThat(response.seatNumber()).isEqualTo("A-1");
  }

  @Test
  @DisplayName("성공: 관리자 목록은 status 집합을 그대로 유스케이스에 넘긴다 (#674)")
  void getAdminBookings_delegates_statuses() {
    // given
    OffsetPageRequest pageRequest = new OffsetPageRequest(0, 10);
    Set<BookingStatus> statuses = Set.of(BookingStatus.PENDING, BookingStatus.REFUNDING);
    given(bookingGetAdminBookingsUseCase.execute(statuses, pageRequest))
        .willReturn(new PageImpl<>(List.of(adminBooking(5L, 2L, 3L))));
    given(performanceRestClient.getPerformances(Set.of(2L))).willReturn(Map.of());
    given(seatRestClient.getSeatNumbers(List.of(3L))).willReturn(Map.of());
    given(userRestClient.getUsers(List.of(5L))).willReturn(Map.of());

    // when
    Page<BookingAdminSummaryResponse> page =
        bookingFacade.getAdminBookings(ADMIN_ID, statuses, pageRequest);

    // then: 보강 3축은 status 여부와 무관하게 같은 경로를 탄다
    // (감사 로그는 분기 이전 공통 경로라 코드로 보장되며, 여기서 단언하지는 않는다)
    assertThat(page.getContent()).hasSize(1);
    verify(bookingGetAdminBookingsUseCase).execute(statuses, pageRequest);
    verify(userRestClient).getUsers(List.of(5L));
  }

  @Test
  @DisplayName("성공: 관리자 단건 조회도 공연·좌석·예매자 세 축을 보강한다")
  void getAdminBooking_enriches_all_three_axes() {
    // given: 좌석 관리자 화면이 booking_number로 예매자를 조합하는 경로 (#562)
    Booking booking = confirmedAdminBooking(5L, 2L, 3L);
    given(bookingGetAdminBookingUseCase.execute("BK-5")).willReturn(booking);
    given(performanceRestClient.getPerformance(2L)).willReturn(Optional.of(performanceInfo()));
    given(seatRestClient.getSeatNumbers(List.of(3L))).willReturn(Map.of(3L, "A-1"));
    given(userRestClient.getUsers(List.of(5L)))
        .willReturn(Map.of(5L, new UserSummaryInfoResponse(5L, "김소희", "user@example.com")));

    // when
    BookingAdminSummaryResponse response = bookingFacade.getAdminBooking(ADMIN_ID, "BK-5");

    // then
    assertThat(response.bookingNumber()).isEqualTo("BK-5");
    assertThat(response.performanceTitle()).isEqualTo("오페라의 유령");
    assertThat(response.seatNumber()).isEqualTo("A-1");
    assertThat(response.bookerName()).isEqualTo("김소희");
    assertThat(response.paymentAmount()).isEqualTo(150000L);
  }

  @Test
  @DisplayName("성공: 관리자 단건 조회에서 예매자 조회가 실패해도 그 필드만 비고 나머지는 살아 있다")
  void getAdminBooking_isolates_user_failure() {
    // given: user-service만 실패해 빈 맵을 돌려준다(부분 응답)
    given(bookingGetAdminBookingUseCase.execute("BK-5")).willReturn(adminBooking(5L, 2L, 3L));
    given(performanceRestClient.getPerformance(2L)).willReturn(Optional.of(performanceInfo()));
    given(seatRestClient.getSeatNumbers(List.of(3L))).willReturn(Map.of(3L, "A-1"));
    given(userRestClient.getUsers(List.of(5L))).willReturn(Map.of());

    // when
    BookingAdminSummaryResponse response = bookingFacade.getAdminBooking(ADMIN_ID, "BK-5");

    // then
    assertThat(response.bookerName()).isNull();
    assertThat(response.bookerEmail()).isNull();
    assertThat(response.userId()).isEqualTo(5L); // 프론트 재조회 키는 남는다
    assertThat(response.performanceTitle()).isEqualTo("오페라의 유령");
    assertThat(response.seatNumber()).isEqualTo("A-1");
  }

  @Test
  @DisplayName("성공: 통계는 원격 호출 없이 DB 집계만으로 응답한다")
  void getAdminBookingStats_does_not_call_remote_services() {
    // given: 매출이 예매가 보유한 결제 금액의 합이라 공연 가격을 되물을 필요가 없다 (#561)
    BookingAdminStatsResponse stats = new BookingAdminStatsResponse(9, 3, 6, 450000L, true, 0);
    given(bookingGetAdminStatsUseCase.execute()).willReturn(stats);

    // when
    BookingAdminStatsResponse response = bookingFacade.getAdminBookingStats();

    // then: 공연 조회가 없으므로 performance-service 장애가 통계를 흔들지 못한다
    assertThat(response).isEqualTo(stats);
    verifyNoInteractions(performanceRestClient, seatRestClient, userRestClient);
  }

  /** 환불 진행 중이면서 실패 이력이 남은 재시도 건을 만든다 (#675). */
  private static Booking retryingRefundBooking(Long userId, Long performanceId, Long seatId) {
    Booking booking = confirmedAdminBooking(userId, performanceId, seatId);
    booking.requestRefund();
    booking.recordRefundFailure(LocalDateTime.of(2026, 7, 10, 12, 0));
    booking.requestRefund();
    return booking;
  }

  @Test
  @DisplayName("성공: 관리자 환불 목록은 공연과 예매자만 보강한다 — 좌석은 호출하지 않는다 (#675)")
  void getAdminRefunds_enriches_performance_and_user_without_seat() {
    // given: 환불 화면은 좌석 번호를 그리지 않는다. 좌석까지 부르면 쓰지도 않는 왕복이 한 번 더 든다.
    OffsetPageRequest pageRequest = new OffsetPageRequest(0, 10);
    given(bookingGetAdminRefundsUseCase.execute(null, pageRequest))
        .willReturn(new PageImpl<>(List.of(retryingRefundBooking(5L, 2L, 3L))));
    given(performanceRestClient.getPerformances(Set.of(2L)))
        .willReturn(Map.of(2L, performanceInfo()));
    given(userRestClient.getUsers(List.of(5L)))
        .willReturn(Map.of(5L, new UserSummaryInfoResponse(5L, "김소희", "user@example.com")));

    // when
    Page<BookingAdminRefundSummaryResponse> page =
        bookingFacade.getAdminRefunds(ADMIN_ID, null, pageRequest);

    // then
    BookingAdminRefundSummaryResponse response = page.getContent().get(0);
    assertThat(response.performanceTitle()).isEqualTo("오페라의 유령");
    assertThat(response.performanceDate()).isEqualTo(LocalDate.of(2026, 5, 22));
    assertThat(response.performanceTime()).isEqualTo(LocalTime.of(19, 30));
    assertThat(response.bookerName()).isEqualTo("김소희");
    assertThat(response.paymentAmount()).isEqualTo(150000L);
    verifyNoInteractions(seatRestClient);
  }

  @Test
  @DisplayName("성공: 실패 이력이 남은 재시도 건은 실패가 아니라 진행 중으로 응답된다 (#675)")
  void getAdminRefunds_classifies_retried_booking_as_in_progress() {
    // given: refundFailedAt은 재환불 시 지워지지 않는다(ADR 0005). 그 값으로 분류하면 재시도 중인
    // 예매가 실패 탭에 남아 CS가 이미 걸어 둔 환불을 다시 건다.
    OffsetPageRequest pageRequest = new OffsetPageRequest(0, 10);
    given(bookingGetAdminRefundsUseCase.execute(null, pageRequest))
        .willReturn(new PageImpl<>(List.of(retryingRefundBooking(5L, 2L, 3L))));
    given(performanceRestClient.getPerformances(Set.of(2L))).willReturn(Map.of());
    given(userRestClient.getUsers(List.of(5L))).willReturn(Map.of());

    // when
    Page<BookingAdminRefundSummaryResponse> page =
        bookingFacade.getAdminRefunds(ADMIN_ID, null, pageRequest);

    // then: 예매 상태와 환불 처리 상태가 다른 축으로 내려가고, 실패 시각은 이력으로만 남는다
    BookingAdminRefundSummaryResponse response = page.getContent().get(0);
    assertThat(response.bookingStatus()).isEqualTo(BookingStatus.REFUNDING);
    assertThat(response.refundStatus()).isEqualTo(RefundProcessStatus.IN_PROGRESS);
    assertThat(response.refundFailedAt()).isEqualTo(LocalDateTime.of(2026, 7, 10, 12, 0));
  }

  @Test
  @DisplayName("성공: 진행·완료·실패가 섞인 페이지가 행마다 다른 환불 처리 상태로 매핑된다 (#675)")
  void getAdminRefunds_maps_every_refund_kind_in_one_page() {
    // given: 세 종류를 한 페이지에 섞는다. 한 종류만 쓰면 DTO 매핑이 세 분기 중 하나만 지나가서,
    // 나머지 두 분기가 틀려도 전부 그린이 된다.
    Booking completed = confirmedAdminBooking(6L, 2L, 4L);
    completed.requestRefund();
    completed.markRefunded();

    Booking failed = confirmedAdminBooking(7L, 2L, 5L);
    failed.requestRefund();
    failed.recordRefundFailure(LocalDateTime.of(2026, 7, 10, 12, 0));

    OffsetPageRequest pageRequest = new OffsetPageRequest(0, 10);
    given(bookingGetAdminRefundsUseCase.execute(null, pageRequest))
        .willReturn(new PageImpl<>(List.of(retryingRefundBooking(5L, 2L, 3L), completed, failed)));
    given(performanceRestClient.getPerformances(Set.of(2L))).willReturn(Map.of());
    given(userRestClient.getUsers(List.of(5L, 6L, 7L))).willReturn(Map.of());

    // when
    Page<BookingAdminRefundSummaryResponse> page =
        bookingFacade.getAdminRefunds(ADMIN_ID, null, pageRequest);

    // then: 예매 상태와 환불 처리 상태가 행마다 짝을 이룬다
    assertThat(page.getContent())
        .extracting(
            BookingAdminRefundSummaryResponse::bookingStatus,
            BookingAdminRefundSummaryResponse::refundStatus)
        .containsExactly(
            tuple(BookingStatus.REFUNDING, RefundProcessStatus.IN_PROGRESS),
            tuple(BookingStatus.REFUNDED, RefundProcessStatus.COMPLETED),
            tuple(BookingStatus.CONFIRMED, RefundProcessStatus.FAILED));

    // 실패 시각은 실패 건과 재시도 건 모두에 실리고, 환불 완료 건에는 이력이 없다
    assertThat(page.getContent())
        .extracting(BookingAdminRefundSummaryResponse::refundFailedAt)
        .containsExactly(
            LocalDateTime.of(2026, 7, 10, 12, 0), null, LocalDateTime.of(2026, 7, 10, 12, 0));
  }

  @Test
  @DisplayName("성공: 환불 목록에서 공연 조회가 실패해도 결제 금액과 재조회 키는 살아남는다 (#675)")
  void getAdminRefunds_keeps_amount_when_performance_lookup_fails() {
    // given: performance-service가 죽어 공연 필드를 못 채우는 상황 (부분 응답)
    OffsetPageRequest pageRequest = new OffsetPageRequest(0, 10);
    given(bookingGetAdminRefundsUseCase.execute(null, pageRequest))
        .willReturn(new PageImpl<>(List.of(retryingRefundBooking(5L, 2L, 3L))));
    given(performanceRestClient.getPerformances(Set.of(2L))).willReturn(Map.of());
    given(userRestClient.getUsers(List.of(5L))).willReturn(Map.of());

    // when
    Page<BookingAdminRefundSummaryResponse> page =
        bookingFacade.getAdminRefunds(ADMIN_ID, null, pageRequest);

    // then: 금액은 예매가 보유하므로 다른 서비스 장애와 무관하다
    BookingAdminRefundSummaryResponse response = page.getContent().get(0);
    assertThat(response.performanceTitle()).isNull();
    assertThat(response.performanceTime()).isNull();
    assertThat(response.bookerName()).isNull();
    assertThat(response.performanceId()).isEqualTo(2L);
    assertThat(response.userId()).isEqualTo(5L);
    assertThat(response.paymentAmount()).isEqualTo(150000L);
  }

  @Test
  @DisplayName("성공: 환불 목록은 refundStatus 필터를 그대로 유스케이스에 넘긴다 (#675)")
  void getAdminRefunds_delegates_refund_status() {
    // given
    OffsetPageRequest pageRequest = new OffsetPageRequest(0, 10);
    given(bookingGetAdminRefundsUseCase.execute(RefundProcessStatus.FAILED, pageRequest))
        .willReturn(new PageImpl<>(List.of()));
    given(performanceRestClient.getPerformances(Set.of())).willReturn(Map.of());
    given(userRestClient.getUsers(List.of())).willReturn(Map.of());

    // when
    bookingFacade.getAdminRefunds(ADMIN_ID, RefundProcessStatus.FAILED, pageRequest);

    // then
    verify(bookingGetAdminRefundsUseCase).execute(RefundProcessStatus.FAILED, pageRequest);
  }

  @Test
  @DisplayName("성공: 환불 요약 통계는 다른 서비스를 호출하지 않는다 (#675)")
  void getAdminRefundStats_does_not_call_remote_services() {
    // given: 예매가 환불 결과를 이미 보유하므로 DB 집계 한 번으로 끝난다
    BookingRefundStatsResponse stats = new BookingRefundStatsResponse(312, 280);
    given(bookingGetAdminRefundStatsUseCase.execute()).willReturn(stats);

    // when
    BookingRefundStatsResponse response = bookingFacade.getAdminRefundStats();

    // then
    assertThat(response).isEqualTo(stats);
    verifyNoInteractions(performanceRestClient, seatRestClient, userRestClient);
  }

  @Test
  @DisplayName("성공: 관리자 환불 처리는 입장 완료 검증을 유스케이스보다 먼저 수행한다")
  void refundBooking_validates_ticket_before_usecase() {
    // given
    Long adminId = 99L;
    String bookingNumber = "BK-1";

    // when
    bookingFacade.refundBooking(adminId, bookingNumber);

    // then
    InOrder inOrder = inOrder(bookingValidateTicketNotUsedUseCase, bookingAdminRefundUseCase);
    inOrder.verify(bookingValidateTicketNotUsedUseCase).executeForAdmin(bookingNumber);
    inOrder.verify(bookingAdminRefundUseCase).execute(adminId, bookingNumber);

    // D-7은 걸지 않는다 (#668). 정책 예외를 처리하려고 있는 CS 창구라 막으면 대응 수단이 사라진다.
    verifyNoInteractions(bookingValidateRefundDeadlineUseCase);
  }

  @Test
  @DisplayName("실패: 입장 완료 예매는 관리자 환불 처리도 차단하고 유스케이스를 호출하지 않는다")
  void refundBooking_blocked_when_ticket_used() {
    // given
    Long adminId = 99L;
    String bookingNumber = "BK-1";
    doThrow(new BusinessException(BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED))
        .when(bookingValidateTicketNotUsedUseCase)
        .executeForAdmin(bookingNumber);

    // when & then
    assertThatThrownBy(() -> bookingFacade.refundBooking(adminId, bookingNumber))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED);

    verifyNoInteractions(bookingAdminRefundUseCase);
  }
}
