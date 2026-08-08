package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsByPerformanceResponse;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전 공연 좌석 수 일괄 집계 (#563).
 *
 * <p>공연별로 {@link SeatGetStatusCountsUseCase}를 N번 부르는 대신 한 번의 GROUP BY로 끝낸다. 관리자 대시보드의 평균 점유율은 전체
 * 공연이 모수라, 공연 수만큼 원격 호출이 반복되면 공연이 늘어날수록 선형으로 느려지고 중간에 하나만 실패해도 평균이 왜곡된다.
 *
 * <p><b>좌석이 하나도 없는 공연은 이 응답에 나타나지 않는다.</b> GROUP BY는 행이 있는 그룹만 만들기 때문이다. 좌석 생성은 {@code
 * PerformanceCreatedEvent}를 받아 비동기로 일어나므로, 공연이 방금 등록됐고 아직 좌석이 생성되지 않은 창에서 실제로 발생한다. 호출자는 이 경우를 0으로
 * 채우지 말고 <b>모르는 값</b>으로 다뤄야 한다 — 0으로 채우면 점유율 분모가 0이 되고, 좌석이 곧 생길 공연이 "매진 불가능한 공연"으로 집계에 섞인다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SeatGetAllStatusCountsUseCase {

  private final SeatRepository seatRepository;

  /**
   * {@code performanceIds}를 주면 그 공연들만, null이거나 비어 있으면 전 공연을 집계한다 (#590).
   *
   * <p>관리자 대시보드는 전 공연이 모수라 필터 없이 부르고, 관리자 공연 목록은 현재 페이지의 공연만 실어 보낸다.
   */
  public List<SeatStatusCountsByPerformanceResponse> execute(List<Long> performanceIds) {
    return seatRepository.getStatusCountsGroupedByPerformance(LocalDateTime.now(), performanceIds);
  }
}
