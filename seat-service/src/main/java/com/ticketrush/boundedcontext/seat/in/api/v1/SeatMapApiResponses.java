package com.ticketrush.boundedcontext.seat.in.api.v1;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 좌석맵 조회 응답 문서(#469). 캐시 스플라이스(RawValue) 도입으로 컨트롤러 반환 타입이 {@code ApiResponse<Object>}가 되면서 result
 * 스키마가 타입 추론으로는 드러나지 않아, {@link SeatCountsApiResponses}와 같은 방식(래퍼 스키마 + 예시)으로 와이어 포맷을 명시한다.
 *
 * <p>result는 {@code SeatMapResponse} 객체다(#645, 이전에는 좌석 배열). 배치도 미생성 응답도 계약이라 예시로 함께 둔다.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApiResponses({
  @ApiResponse(
      responseCode = "200",
      description = "좌석맵 조회 성공 — result는 SeatMapResponse({ layout, seats })",
      content =
          @Content(
              mediaType = "application/json",
              schema =
                  @Schema(implementation = com.ticketrush.global.dto.response.ApiResponse.class),
              examples = {
                @ExampleObject(
                    name = "Success",
                    summary = "정상 응답 (hold_expired_at은 HOLD 좌석에만 존재)",
                    value =
                        """
                        {
                          "is_success": true,
                          "code": "COMMON_200",
                          "message": "요청에 성공하였습니다.",
                          "trace_id": "trace-id-example",
                          "result": {
                            "layout": {
                              "total_rows": 10,
                              "max_cols": 12
                            },
                            "seats": [
                              {
                                "seat_id": 1,
                                "seat_layout_id": 101,
                                "seat_number": "A-1",
                                "seat_row": 1,
                                "seat_col": 1,
                                "seat_status": "AVAILABLE"
                              },
                              {
                                "seat_id": 2,
                                "seat_layout_id": 101,
                                "seat_number": "A-2",
                                "seat_row": 1,
                                "seat_col": 2,
                                "seat_status": "HOLD",
                                "hold_expired_at": "2026-08-01T12:00:00Z"
                              }
                            ]
                          }
                        }
                        """),
                @ExampleObject(
                    name = "LayoutNotCreated",
                    summary = "배치도 미생성 (좌석 생성 전)",
                    value =
                        """
                        {
                          "is_success": true,
                          "code": "COMMON_200",
                          "message": "요청에 성공하였습니다.",
                          "trace_id": "trace-id-example",
                          "result": {
                            "layout": null,
                            "seats": []
                          }
                        }
                        """)
              }))
})
public @interface SeatMapApiResponses {}
