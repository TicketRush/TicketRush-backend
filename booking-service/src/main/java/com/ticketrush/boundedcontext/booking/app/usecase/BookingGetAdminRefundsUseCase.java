package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.domain.types.RefundProcessStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 환불 대상 예매를 통합 조회한다 (#675). 어떤 예매를 환불 대상으로 볼지는 이 유스케이스가 소유한다 — 기존 관리자 조회들이 대상 상태를 스스로 정하는 것과
 * 같은 규율이다.
 *
 * <p><b>{@code BookingAdminRefundSummaryResponse}로 감싸지 않고 엔티티를 반환한다.</b> 공연·예매자 보강이 파사드의 일이고, 그 보강
 * 결과와 합쳐야 DTO가 완성되기 때문이다 — {@code BookingGetAdminBookingsUseCase}와 같은 이유다.
 *
 * <p>기존 환불 관리자 조회 둘을 대체하지 않는다. {@code /bookings/refund-failed}는 미해결 실패만, {@code
 * /bookings/refunding-stuck}은 임계 초과 고착만 보며 각자의 정렬(실패 시각 · 진입 시각)이 그 용도의 핵심이다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingGetAdminRefundsUseCase {

  private final BookingRepository bookingRepository;

  /**
   * 최신 예매부터 반환한다. {@code refundStatus}가 null이면 환불 진행·완료·미해결 실패 전체를 조회한다.
   *
   * <p>정렬은 네 경로 모두 전역 {@code id DESC}다. 필터를 바꿔도 순서 규칙이 흔들리지 않고, 같은 요청 안에서는 목록과 count가 같은 조건을 보므로
   * 상태별 페이지를 합칠 때 생기는 누락이 없다. {@code /bookings/refund-failed}가 쓰는 {@code refundFailedAt}은 이 목록의 정렬
   * 키가 될 수 없다 — REFUNDING·REFUNDED 행에서는 null일 수 있다.
   *
   * <p><b>다만 offset 페이징이라 페이지 사이의 쓰기까지 막지는 못한다.</b> auto_increment id의 단조성은 새 행이 정렬 맨 앞으로만 들어올 때
   * offset을 보호하는데, 이 목록의 모집단에 새로 들어오는 것은 INSERT가 아니라 <b>기존 예매의 상태 전이</b>(CONFIRMED → REFUNDING)다. 그
   * 예매의 id는 몇 달 전 값일 수 있어 정렬 중간에 끼어들고, 1페이지를 읽은 뒤 그 구간에 한 건이 끼면 2페이지에서 경계의 1건이 밀려 보이지 않는다. 커서 페이징이
   * 필요한 문제이고 이 화면만의 것도 아니라 여기서 바꾸지 않았다 — 관리자가 목록을 새로고침하면 다시 드러난다. 리포지토리 테스트가 고정하는 것은 <b>쓰기 없는 스냅샷
   * 안에서의</b> 누락·중복 없음까지다.
   *
   * <p>필터가 걸린 세 경로는 기존 조회 메서드를 그대로 쓴다. 각 상태의 조건이 이미 한 조건으로 표현되므로 통합 쿼리를 태울 이유가 없고, 조건이 두 벌로 갈리지도
   * 않는다 — 통합 쿼리의 세 분기가 곧 이 세 메서드다.
   */
  public Page<Booking> execute(RefundProcessStatus refundStatus, OffsetPageRequest pageRequest) {
    PageRequest pageable =
        PageRequest.of(pageRequest.page(), pageRequest.size(), Sort.by(Sort.Order.desc("id")));

    if (refundStatus == null) {
      return bookingRepository.findRefundTargets(
          BookingStatus.REFUNDING, BookingStatus.REFUNDED, BookingStatus.CONFIRMED, pageable);
    }

    return switch (refundStatus) {
      case IN_PROGRESS ->
          bookingRepository.findByBookingStatusIn(Set.of(BookingStatus.REFUNDING), pageable);
      case COMPLETED ->
          bookingRepository.findByBookingStatusIn(Set.of(BookingStatus.REFUNDED), pageable);
      case FAILED ->
          bookingRepository.findByBookingStatusAndRefundFailedAtIsNotNull(
              BookingStatus.CONFIRMED, pageable);
    };
  }
}
