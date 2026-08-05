package com.ticketrush.boundedcontext.seat.in.api.v1;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatNumberResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsResponse;
import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.util.RawValue;

@Validated
@RestController
@RequestMapping("/api/v1/seat")
@RequiredArgsConstructor
public class SeatController {

  private final SeatFacade seatFacade;

  @GetMapping("/{performanceId}/seat-layouts")
  @Operation(
      summary = "공연별 좌석 배치 조회",
      description =
          """
          공연 ID에 해당하는 좌석 ID, 좌석 배치 ID, 좌석 번호, 좌석 상태, 선점 만료 시각을 조회합니다. 인증이 필요 없습니다.

          **선점 만료 시각(`hold_expired_at`)은 인증 없이 계속 공개합니다(#562 검토 결론).** 이 값은 예매자를 식별하는
          개인정보가 아니라 "이 좌석이 언제 다시 예매 가능해지는지"라는 좌석 상태의 일부이며, 예매 화면이 선점 해제를
          기다리는 근거로 씁니다. 관리자 경계(`/api/v1/seat/admin/**`)를 세우면서 이 필드의 노출 범위도 함께 검토했으나,
          축소하면 공개 좌석맵과 SSE의 응답 스펙이 깨지는 대가에 비해 얻는 것이 없어 현행을 유지합니다.
          예매자를 식별하는 값(예매 번호)은 관리자 API에서만 내려갑니다.

          응답은 최대 30초 캐시될 수 있습니다. 좌석 상태가 바뀌면 캐시가 즉시 무효화되므로 정상 경로에서는 지연되지 않습니다.
          """)
  @SeatMapApiResponses
  public ResponseEntity<ApiResponse<Object>> getSeatMap(@PathVariable Long performanceId) {
    // 파사드가 캐시/직렬화한 JSON 배열을 재파싱 없이 result 자리에 그대로 끼운다(#469). 래퍼를 응답째 캐싱하지
    // 않는 이유는 ApiResponse.traceId가 요청마다 새로 뽑히기 때문 — 래퍼만 매번 만들고 배열은 RawValue로 스플라이스.
    String seatMapJson = seatFacade.getPerformanceSeatMap(performanceId);
    return ApiResponse.onSuccess(SuccessStatus.OK, new RawValue(seatMapJson));
  }

  @GetMapping("/numbers")
  @Operation(summary = "좌석 번호 목록 조회", description = "좌석 ID 목록에 해당하는 좌석 번호를 조회합니다.")
  public ResponseEntity<ApiResponse<List<SeatNumberResponse>>> getSeatNumbers(
      @RequestParam @Size(min = 1, max = 120) List<Long> seatIds) {
    List<SeatNumberResponse> response = seatFacade.getSeatNumbers(seatIds);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @GetMapping("/{performanceId}/seat-counts")
  @Operation(
      summary = "공연 좌석 상태별 수 조회",
      description = "공연 ID로 전체, 예매 가능, 판매 완료, 임시 선점 좌석 수를 조회합니다.")
  @SeatCountsApiResponses
  public ResponseEntity<ApiResponse<SeatStatusCountsResponse>> getSeatCounts(
      @Parameter(description = "공연 ID") @PathVariable Long performanceId) {
    SeatStatusCountsResponse response = seatFacade.getPerformanceSeatStatusCounts(performanceId);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @GetMapping(
      value = "/{performanceId}/seat-status/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(summary = "공연별 좌석 상태 SSE 구독", description = "공연 ID에 해당하는 좌석 상태 변경 이벤트를 실시간으로 구독합니다.")
  public SseEmitter subscribeSeatStatus(@PathVariable Long performanceId) {
    return seatFacade.subscribeSeatStatus(performanceId);
  }
}
