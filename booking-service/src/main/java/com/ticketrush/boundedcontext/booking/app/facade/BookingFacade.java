package com.ticketrush.boundedcontext.booking.app.facade;

import com.ticketrush.boundedcontext.booking.app.dto.request.BookingCreateRequest;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingCountResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingDetailResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingMySummaryResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPendingResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingAdminRetryRefundUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCancelMyBookingUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCountUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCreateUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetMyBookingUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetMyBookingsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetRefundFailedBookingsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetRefundingStuckBookingsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingIssueNumberUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateReferencesUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateSeatAvailableUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateTicketNotUsedUseCase;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.apiclient.PerformanceRestClient;
import com.ticketrush.boundedcontext.booking.out.apiclient.SeatRestClient;
import com.ticketrush.boundedcontext.booking.out.apiclient.dto.PerformanceInfoResponse;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingFacade {

  private final BookingIssueNumberUseCase bookingIssueNumberUseCase;
  private final BookingCreateUseCase bookingCreateUseCase;
  private final BookingGetMyBookingUseCase bookingGetMyBookingUseCase;
  private final BookingGetMyBookingsUseCase bookingGetMyBookingsUseCase;
  private final PerformanceRestClient performanceRestClient;
  private final BookingCountUseCase bookingCountUseCase;
  private final BookingCancelMyBookingUseCase bookingCancelMyBookingUseCase;
  private final SeatRestClient seatRestClient;
  private final BookingValidateReferencesUseCase bookingValidateReferencesUseCase;
  private final BookingValidateSeatAvailableUseCase bookingValidateSeatAvailableUseCase;
  private final BookingValidateTicketNotUsedUseCase bookingValidateTicketNotUsedUseCase;
  private final BookingGetRefundFailedBookingsUseCase bookingGetRefundFailedBookingsUseCase;
  private final BookingGetRefundingStuckBookingsUseCase bookingGetRefundingStuckBookingsUseCase;
  private final BookingAdminRetryRefundUseCase bookingAdminRetryRefundUseCase;

  public BookingPendingResponse createBooking(Long userId, Long performanceId, Long seatId) {
    // 참조 및 좌석 가용성 검증 실행
    bookingValidateReferencesUseCase.execute(userId, performanceId, seatId);
    bookingValidateSeatAvailableUseCase.execute(seatId, performanceId);

    // 고유 예약 번호 발급
    String bookingNumber = bookingIssueNumberUseCase.execute();

    // PENDING 상태로 예매 생성
    BookingCreateRequest request =
        new BookingCreateRequest(userId, performanceId, seatId, bookingNumber);

    Booking booking = bookingCreateUseCase.execute(request);

    return BookingPendingResponse.from(booking);
  }

  /**
   * 본인 예매 단건 조회 (#560). DB 조회(트랜잭션 안)와 타 도메인 보강(트랜잭션 밖)을 여기서 잇는다 — UseCase에서 원격 호출을 하면 DB 커넥션을 쥔 채
   * 타임아웃을 대기하게 된다.
   *
   * <p>보강 실패는 각 클라이언트가 빈 값으로 흡수하므로(부분 응답) 여기에는 catch가 없다. 한 도메인의 실패가 다른 도메인 필드에 닿는 경로도 없다.
   */
  public BookingDetailResponse getMyBooking(Long userId, String bookingNumber) {
    Booking booking = bookingGetMyBookingUseCase.execute(userId, bookingNumber);

    PerformanceInfoResponse performance =
        performanceRestClient.getPerformance(booking.getPerformanceId()).orElse(null);
    String seatNumber =
        seatRestClient.getSeatNumbers(List.of(booking.getSeatId())).get(booking.getSeatId());

    return BookingDetailResponse.of(booking, performance, seatNumber);
  }

  /**
   * 내 예매 목록 조회 (#560). 단건 조회와 같은 축으로 공연·좌석을 보강한다 — distinct 공연 묶음 조회 + 좌석 벌크 1회. 보강 실패는 각 클라이언트가
   * 흡수해 해당 도메인 필드만 null이다(부분 응답).
   */
  public Page<BookingMySummaryResponse> getMyBookings(
      Long userId, BookingStatus bookingStatus, OffsetPageRequest pageRequest) {
    Page<BookingSummaryResponse> page =
        bookingGetMyBookingsUseCase.execute(userId, bookingStatus, pageRequest);
    List<BookingSummaryResponse> content = page.getContent();

    Map<Long, PerformanceInfoResponse> performances =
        performanceRestClient.getPerformances(
            content.stream()
                .map(BookingSummaryResponse::performanceId)
                .collect(Collectors.toSet()));
    Map<Long, String> seatNumbers =
        seatRestClient.getSeatNumbers(
            content.stream().map(BookingSummaryResponse::seatId).distinct().toList());

    return page.map(
        summary ->
            BookingMySummaryResponse.from(
                summary,
                performances.get(summary.performanceId()),
                seatNumbers.get(summary.seatId())));
  }

  public BookingCountResponse countMyBookings(Long userId, BookingStatus bookingStatus) {
    return bookingCountUseCase.execute(userId, bookingStatus);
  }

  /**
   * 사용자의 예매 취소. 예매 상태로 경로가 갈린다 (#559).
   *
   * <ul>
   *   <li><b>PENDING</b> — 결제 전 이탈이다. 즉시 CANCELED로 종결하고 선점 좌석을 반납한다.
   *   <li><b>그 외(CONFIRMED)</b> — 기존 환불 플로우 그대로. CONFIRMED→REFUNDING 전이 후 환불 성공 이벤트에 좌석 반환을
   *       매단다(#91).
   * </ul>
   *
   * <p>좌석 반납을 취소 트랜잭션 <b>밖</b>에서 하는 것이 중요하다. seat가 되쏘는 {@code SeatHoldExpiredEvent}를 booking이 받아
   * 예매를 EXPIRED로 전이시키려 하는데, CANCELED 커밋이 먼저 끝나 있어야 {@code WHERE bookingStatus = PENDING} 가드가 그것을
   * no-op으로 막는다.
   */
  public void cancelMyBooking(Long userId, String bookingNumber) {
    // 입장 완료 예매의 환불 차단 (#399). 소유권을 함께 검증해 비소유자에게 타인 예매의 입장 여부가 새지 않게 한다.
    // PENDING은 환불이 성사될 수 없어 isRefundable()에서 제외되므로 ticket-service 왕복이 일어나지 않는다.
    bookingValidateTicketNotUsedUseCase.execute(userId, bookingNumber);

    bookingCancelMyBookingUseCase
        .execute(userId, bookingNumber)
        .ifPresent(seatId -> seatRestClient.releaseHold(bookingNumber, seatId));
  }

  public Page<BookingSummaryResponse> getRefundFailedBookings(OffsetPageRequest pageRequest) {
    return bookingGetRefundFailedBookingsUseCase.execute(pageRequest);
  }

  public Page<BookingSummaryResponse> getRefundingStuckBookings(OffsetPageRequest pageRequest) {
    return bookingGetRefundingStuckBookingsUseCase.execute(pageRequest);
  }

  public void retryRefund(Long adminId, String bookingNumber) {
    // 관리자 재환불에도 같은 정책을 적용한다 (#399). 사용자만 막고 CS 도구로 우회되면 좌석은 여전히 반환된다.
    // 다만 차단 대상은 CONFIRMED에서의 환불 개시뿐이다 — REFUNDING 고착 재발행(#397)은 통과시킨다.
    // 막으면 REFUNDING을 빠져나올 유일한 수단이 사라져 흡수 상태가 되살아난다(가드 Javadoc 참고).
    bookingValidateTicketNotUsedUseCase.executeForAdmin(bookingNumber);

    bookingAdminRetryRefundUseCase.execute(adminId, bookingNumber);
  }
}
