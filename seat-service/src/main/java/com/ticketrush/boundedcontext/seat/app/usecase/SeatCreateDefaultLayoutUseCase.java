package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.domain.entity.SeatLayout;
import com.ticketrush.boundedcontext.seat.out.repository.SeatLayoutRepository;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import com.ticketrush.shared.performance.event.PerformanceCreatedEvent;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공연 등록 시 기본 좌석 배치도와 좌석을 생성한다. 좌석 수는 등록 요청의 총 좌석 수를 따른다(#590).
 *
 * <p><b>행은 26개가 상한이다.</b> 좌석 번호의 행 이름이 {@code (char)('A' + row)}라 27행부터 {@code '['}·{@code '\'} 같은
 * 문자로 넘어간다. 그래서 좌석이 늘어나면 행이 아니라 열을 늘린다.
 *
 * <p><b>SeatLayout의 totalRows × maxCols는 실제 좌석 수와 다를 수 있다.</b> 마지막 행이 부분 행이기 때문이다(10,000석이면 26행 ×
 * 385열 = 10,010칸 중 10,000석만 만들고 Z행은 375석에서 끊긴다). 이 두 값을 곱해 좌석 수를 유도하는 코드를 만들면 안 된다 — 좌석 수의 원본은 seat
 * 테이블의 행 수다. 현재 이 둘을 읽는 곳은 이 클래스의 생성 루프뿐이다(#590 시점 grep 확인).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatCreateDefaultLayoutUseCase {

  private static final int DEFAULT_TOTAL_SEATS = 120;
  private static final int MAX_ROWS = 26;
  private static final int DEFAULT_MAX_COLS = 12;

  private final SeatRepository seatRepository;
  private final SeatLayoutRepository seatLayoutRepository;

  @Transactional
  public void execute(Long performanceId, Integer requestedSeats) {
    if (seatLayoutRepository.existsByPerformanceId(performanceId)) {
      log.info("이미 좌석 배치도가 생성된 공연입니다. 좌석 생성을 스킵합니다. performanceId: {}", performanceId);
      return;
    }

    int totalSeats = resolveTotalSeats(performanceId, requestedSeats);
    int totalRows = Math.min(ceilDiv(totalSeats, DEFAULT_MAX_COLS), MAX_ROWS);
    int maxCols = (totalRows < MAX_ROWS) ? DEFAULT_MAX_COLS : ceilDiv(totalSeats, MAX_ROWS);

    SeatLayout savedLayout =
        seatLayoutRepository.saveAndFlush(
            SeatLayout.builder()
                .performanceId(performanceId)
                .totalRows(totalRows)
                .maxCols(maxCols)
                .build());

    List<Seat> seats = createSeats(savedLayout, totalSeats);
    // ponytail: IDENTITY PK라 Hibernate가 INSERT 배치를 원천 비활성화한다 — hibernate.jdbc.batch_size를 켜도
    // 이 경로에는 효과가 없다. 상한 10,000석이면 개별 INSERT 10,000회가 inbox 트랜잭션 안에서 돈다.
    // 공연 등록은 관리자 저빈도 행위라 수용한다. 느려지면 JdbcTemplate.batchUpdate로 내리는 것이 유일한
    // 상향 경로다(PK 전략을 바꾸는 것은 seat 전체에 영향이 간다).
    seatRepository.saveAll(seats);

    log.info("기본 좌석 배치도와 좌석을 생성했습니다. performanceId: {}, seats: {}", performanceId, seats.size());
  }

  /**
   * 좌석 수를 확정한다. null·1 미만은 기본값으로, 상한 초과는 잘라 낸다.
   *
   * <p>등록 검증({@code @Max})을 통과한 값만 오는 것이 정상이지만, 배포 이전에 발행된 이벤트와 DLT 재처리는 그 검증을 거치지 않은 페이로드를 실어 온다.
   * 리스너는 신뢰 경계 밖이라 좌석 서비스가 스스로 닫는다.
   */
  private int resolveTotalSeats(Long performanceId, Integer requestedSeats) {
    if (requestedSeats == null || requestedSeats < 1) {
      log.info(
          "총 좌석 수가 없어 기본값으로 생성합니다. performanceId: {}, requested: {}, default: {}",
          performanceId,
          requestedSeats,
          DEFAULT_TOTAL_SEATS);
      return DEFAULT_TOTAL_SEATS;
    }

    if (requestedSeats > PerformanceCreatedEvent.MAX_TOTAL_SEATS) {
      log.warn(
          "총 좌석 수가 상한을 넘어 상한으로 생성합니다. performanceId: {}, requested: {}, clamped: {}",
          performanceId,
          requestedSeats,
          PerformanceCreatedEvent.MAX_TOTAL_SEATS);
      return PerformanceCreatedEvent.MAX_TOTAL_SEATS;
    }

    return requestedSeats;
  }

  private List<Seat> createSeats(SeatLayout seatLayout, int totalSeats) {
    List<Seat> seats = new ArrayList<>(totalSeats);

    outer:
    for (int row = 0; row < seatLayout.getTotalRows(); row++) {
      char rowName = (char) ('A' + row);

      for (int col = 1; col <= seatLayout.getMaxCols(); col++) {
        // 그리드 칸 수가 아니라 좌석 수로 끊는다. 마지막 행은 부분 행이 될 수 있다.
        if (seats.size() == totalSeats) {
          break outer;
        }

        seats.add(
            Seat.builder()
                .seatLayoutId(seatLayout.getId())
                .performanceId(seatLayout.getPerformanceId())
                .seatNumber(rowName + "-" + col)
                .seatStatus(SeatStatus.AVAILABLE)
                .holdExpiredAt(null)
                .build());
      }
    }

    return seats;
  }

  private static int ceilDiv(int dividend, int divisor) {
    return (dividend + divisor - 1) / divisor;
  }
}
