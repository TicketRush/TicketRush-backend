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

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApiResponses({
  @ApiResponse(
      responseCode = "200",
      description = "좌석 수 조회 성공",
      content =
          @Content(
              mediaType = "application/json",
              schema =
                  @Schema(implementation = com.ticketrush.global.dto.response.ApiResponse.class),
              examples =
                  @ExampleObject(
                      name = "Success",
                      summary = "정상 응답",
                      value =
                          """
                          {
                            "is_success": true,
                            "code": "COMMON_200",
                            "message": "요청에 성공하였습니다.",
                            "trace_id": "trace-id-example",
                            "result": {
                              "available_count": 120,
                              "total_count": 120
                            }
                          }
                          """))),
  @ApiResponse(
      responseCode = "400",
      description = "잘못된 요청",
      content =
          @Content(
              mediaType = "application/json",
              schema =
                  @Schema(implementation = com.ticketrush.global.dto.response.ApiResponse.class),
              examples =
                  @ExampleObject(
                      name = "BadRequest",
                      summary = "잘못된 공연 ID",
                      value =
                          """
                          {
                            "is_success": false,
                            "code": "COMMON_400",
                            "message": "잘못된 요청입니다.",
                            "trace_id": "trace-id-example"
                          }
                          """))),
  @ApiResponse(
      responseCode = "500",
      description = "서버 오류",
      content =
          @Content(
              mediaType = "application/json",
              schema =
                  @Schema(implementation = com.ticketrush.global.dto.response.ApiResponse.class),
              examples =
                  @ExampleObject(
                      name = "InternalServerError",
                      summary = "서버 내부 오류",
                      value =
                          """
                          {
                            "is_success": false,
                            "code": "COMMON_500",
                            "message": "서버 에러, 관리자에게 문의 바랍니다.",
                            "trace_id": "trace-id-example"
                          }
                          """)))
})
public @interface SeatCountsApiResponses {}
