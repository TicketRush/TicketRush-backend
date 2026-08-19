package com.ticketrush.boundedcontext.performance.app.dto.response;

import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 공연 목록 한 행.
 *
 * <p>{@code totalSeats}·{@code remainingSeats}는 메인 화면 게이지바용 좌석 수이며 <b>seat-service 실측값</b>이다
 * (#176). 엔티티의 {@code Performance.totalSeats}는 공연 등록 시 입력값이라 실제 좌석 수와 무관하므로 게이지의 분모로 쓰면 안 된다 — 매퍼가
 * 두 필드를 {@code ignore}로 막아 둔 이유이며, 그 방어가 풀리면 조용히 등록 입력값이 실린다({@link
 * com.ticketrush.boundedcontext.performance.app.mapper.PerformanceMapper} 참고).
 *
 * <p><b>좌석 수를 모르면 두 필드가 키째 사라진다.</b> 공통 Jackson 설정이 {@code NON_NULL}을 전역으로 걸어 두어 null은 직렬화되지 않는다.
 * 좌석 서비스 조회에 실패했거나 좌석이 아직 생성되지 않은 공연이 여기 해당하며, 클라이언트는 "0석"이 아니라 <b>키의 부재</b>로 판별해야 한다.
 */
public record PerformanceListResponse(
    Long performanceId,
    String title,
    String performer,
    Genre genre,
    LocalDate showDate,
    LocalTime showTime,
    String address,
    String imageMainUrl,
    PerformanceStatus performanceStatus,
    Long price,
    @Schema(
            description =
                "전체 좌석 수. **좌석 서비스가 실제로 생성한 좌석 수**이며 공연 등록 시 입력한 총 좌석 수와는 무관하다. "
                    + "좌석 서비스 조회에 실패했거나 좌석이 아직 생성되지 않은 공연이면 생략된다.",
            example = "500",
            nullable = true)
        Long totalSeats,
    @Schema(
            description =
                "잔여 좌석 수. 전체 좌석에서 판매 완료분을 뺀 값이라 **선점(HOLD) 중인 좌석은 잔여로 센다**. " + "전체 좌석 수와 함께 생략된다.",
            example = "245",
            nullable = true)
        Long remainingSeats) {

  /**
   * 좌석 수를 얹은 복사본을 만든다. 좌석 수를 모르는 공연은 이 메서드를 거치지 않아 두 필드가 null로 남는다.
   *
   * <p>{@code remainingSeats}는 전체에서 판매분을 뺀 값이다. 두 값이 좌석 집계 <b>같은 행</b>에서 나오므로 판매 수가 전체를 넘을 수 없고,
   * 따라서 음수가 되지 않는다.
   */
  public PerformanceListResponse withSeatCounts(Long totalSeats, Long remainingSeats) {
    return new PerformanceListResponse(
        performanceId,
        title,
        performer,
        genre,
        showDate,
        showTime,
        address,
        imageMainUrl,
        performanceStatus,
        price,
        totalSeats,
        remainingSeats);
  }
}
