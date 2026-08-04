package com.ticketrush.boundedcontext.booking.app.facade;

import com.ticketrush.boundedcontext.booking.app.dto.request.BookingCreateRequest;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingCountResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingDetailResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingMySummaryResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPendingResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPerformanceStatsRow;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingAdminRefundUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingAdminRetryRefundUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCancelMyBookingUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCountUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCreateUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetAdminBookingsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetAdminStatsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetMyBookingDetailUseCase;
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
import com.ticketrush.boundedcontext.booking.out.apiclient.UserRestClient;
import com.ticketrush.boundedcontext.booking.out.apiclient.dto.PerformanceInfoResponse;
import com.ticketrush.boundedcontext.booking.out.apiclient.dto.UserSummaryInfoResponse;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import java.util.LinkedHashSet;
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
  private final BookingGetMyBookingDetailUseCase bookingGetMyBookingDetailUseCase;
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
  private final BookingGetAdminBookingsUseCase bookingGetAdminBookingsUseCase;
  private final BookingGetAdminStatsUseCase bookingGetAdminStatsUseCase;
  private final BookingAdminRefundUseCase bookingAdminRefundUseCase;
  private final UserRestClient userRestClient;

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
    Booking booking = bookingGetMyBookingDetailUseCase.execute(userId, bookingNumber);

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

    // 순서를 페이지 순서로 고정한다(LinkedHashSet). 보강이 예산·장애로 중간에 끊길 때 HashSet이면 어느 행이
    // 채워질지가 매 요청 달라져, 새로고침마다 다른 행의 공연 정보가 나타났다 사라진다.
    Map<Long, PerformanceInfoResponse> performances =
        performanceRestClient.getPerformances(
            content.stream()
                .map(BookingSummaryResponse::performanceId)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
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

  /**
   * 관리자 전체 예매 목록 (#561). #560의 보강 축(공연 순차 N + 좌석 벌크 1)에 예매자 벌크 1을 더한다 — 관리자 화면은 예매자가 누구인지가 핵심 컬럼이다.
   *
   * <p>보강 실패는 각 클라이언트가 흡수하므로 여기에 catch가 없다(부분 응답). 한 도메인의 실패가 다른 도메인 필드에 닿는 경로도 없다.
   *
   * <p>세 보강이 직렬이라 최악 벽시계는 공연 3s + 좌석 2s + 예매자 2s다. 관리자 전용 저빈도 경로라 병렬화하지 않았고, 실측상 문제가 되면 순차 N 호출을
   * 없애는 performance-service 벌크 조회가 가장 큰 레버다.
   */
  public Page<BookingAdminSummaryResponse> getAdminBookings(OffsetPageRequest pageRequest) {
    Page<Booking> page = bookingGetAdminBookingsUseCase.execute(pageRequest);
    List<Booking> content = page.getContent();

    // 순서를 페이지 순서로 고정한다(LinkedHashSet). 보강이 예산·장애로 중간에 끊길 때 HashSet이면 어느 행이
    // 채워질지가 매 요청 달라져, 새로고침마다 다른 행의 공연 정보가 나타났다 사라진다.
    Map<Long, PerformanceInfoResponse> performances =
        performanceRestClient.getPerformances(
            content.stream()
                .map(Booking::getPerformanceId)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    Map<Long, String> seatNumbers =
        seatRestClient.getSeatNumbers(content.stream().map(Booking::getSeatId).distinct().toList());
    Map<Long, UserSummaryInfoResponse> users =
        userRestClient.getUsers(content.stream().map(Booking::getUserId).distinct().toList());

    return page.map(
        booking ->
            BookingAdminSummaryResponse.of(
                booking,
                performances.get(booking.getPerformanceId()),
                seatNumbers.get(booking.getSeatId()),
                users.get(booking.getUserId())));
  }

  /**
   * 관리자 예매 요약 통계 (#561). DB 집계는 트랜잭션 안(UseCase), 공연 가격 조회는 밖(여기)이다.
   *
   * <p>매출에 기여하는 공연(완료 예매가 1건 이상)만 조회한다 — 취소만 있는 공연까지 왕복하면 순차 호출이 늘어 예산에 걸릴 확률만 높아지고, 그 결과는 매출이
   * null이 되는 것이다.
   */
  public BookingAdminStatsResponse getAdminBookingStats() {
    List<BookingPerformanceStatsRow> rows = bookingGetAdminStatsUseCase.execute();

    Map<Long, PerformanceInfoResponse> performances =
        performanceRestClient.getPerformances(
            rows.stream()
                .filter(row -> row.confirmedCount() > 0)
                .map(BookingPerformanceStatsRow::performanceId)
                .collect(Collectors.toCollection(LinkedHashSet::new)));

    return BookingAdminStatsResponse.of(rows, performances);
  }

  /**
   * 관리자의 예매 환불 처리 (#561). 재환불 경로와 같은 순서를 지킨다 — 입장 완료 차단을 트랜잭션 밖에서 먼저 검사한다.
   *
   * <p>가드가 고착 REFUNDING을 통과시키더라도 이어지는 {@code requestRefund()}가 REFUNDING을 거절하므로, 이 경로에 잘못된 통과가 생기지
   * 않는다.
   */
  public void refundBooking(Long adminId, String bookingNumber) {
    bookingValidateTicketNotUsedUseCase.executeForAdmin(bookingNumber);

    bookingAdminRefundUseCase.execute(adminId, bookingNumber);
  }

  public void retryRefund(Long adminId, String bookingNumber) {
    // 관리자 재환불에도 같은 정책을 적용한다 (#399). 사용자만 막고 CS 도구로 우회되면 좌석은 여전히 반환된다.
    // 다만 차단 대상은 CONFIRMED에서의 환불 개시뿐이다 — REFUNDING 고착 재발행(#397)은 통과시킨다.
    // 막으면 REFUNDING을 빠져나올 유일한 수단이 사라져 흡수 상태가 되살아난다(가드 Javadoc 참고).
    bookingValidateTicketNotUsedUseCase.executeForAdmin(bookingNumber);

    bookingAdminRetryRefundUseCase.execute(adminId, bookingNumber);
  }
}
