package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingStatsCounts;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 요약 통계 (#561). 매출까지 DB 집계 한 번으로 끝나므로 원격 호출이 없다 — 예매가 확정 시점의 결제 금액을 직접 보유한다.
 *
 * <p>어떤 상태를 완료·취소로 셀지는 이 유스케이스가 소유한다 — 기존 관리자 조회 유스케이스들이 대상 상태를 스스로 정하는 것과 같은 규율이다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingGetAdminStatsUseCase {

  private final BookingRepository bookingRepository;

  /**
   * 요약 통계를 반환한다.
   *
   * <p>완료는 {@code CONFIRMED}, 취소는 {@code CANCELED + REFUNDED}로 센다. <b>{@code EXPIRED}는 취소가 아니다</b>
   * — 결제하지 않아 자동 만료된 것이라 취소로 세면 취소율이 부풀려진다. {@code PENDING}·{@code REFUNDING}은 아직 어느 쪽도 아니다. 즉 네
   * 지표는 서로 배타적 분할이 아니며, 그 사실을 응답 스키마에 명시한다.
   *
   * <p>매출은 완료된 예매의 결제 금액 합이다. 환불된 예매는 돈이 나갔으므로 제외되고, 취소 카드와 모집단이 갈리지 않도록 완료 카드와 같은 상태를 본다.
   */
  public BookingAdminStatsResponse execute() {
    BookingStatsCounts counts =
        bookingRepository.aggregateStats(
            BookingStatus.CONFIRMED, BookingStatus.CANCELED, BookingStatus.REFUNDED);

    return BookingAdminStatsResponse.from(counts);
  }
}
