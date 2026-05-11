package com.ticketrush.boundedcontext.performance.in.api.v1;

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
      responseCode = "400",
      description = "예매 불가 상태",
      content =
          @Content(
              mediaType = "application/json",
              schema =
                  @Schema(implementation = com.ticketrush.global.dto.response.ApiResponse.class),
              examples =
                  @ExampleObject(
                      name = "PerformanceNotOnSale",
                      summary = "예매 가능한 공연이 아님",
                      value =
                          """
                          {
                            "isSuccess": false,
                            "code": "PERFORMANCE_400_005",
                            "message": "예매 가능한 공연이 아닙니다.",
                            "traceId": "trace-id-example"
                          }
                          """))),
  @ApiResponse(
      responseCode = "404",
      description = "공연 없음",
      content =
          @Content(
              mediaType = "application/json",
              schema =
                  @Schema(implementation = com.ticketrush.global.dto.response.ApiResponse.class),
              examples =
                  @ExampleObject(
                      name = "PerformanceNotFound",
                      summary = "공연이 존재하지 않음",
                      value =
                          """
                          {
                            "isSuccess": false,
                            "code": "PERFORMANCE_404_001",
                            "message": "공연이 존재하지 않습니다.",
                            "traceId": "trace-id-example"
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
                            "isSuccess": false,
                            "code": "COMMON_500",
                            "message": "서버 에러, 관리자에게 문의 바랍니다.",
                            "traceId": "trace-id-example"
                          }
                          """)))
})
public @interface PerformanceValidateApiResponses {}
