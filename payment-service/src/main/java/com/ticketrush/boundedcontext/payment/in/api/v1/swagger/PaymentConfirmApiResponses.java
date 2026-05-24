package com.ticketrush.boundedcontext.payment.in.api.v1.swagger;

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
      description = "결제 확정 성공",
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
                            "message": "성공입니다.",
                            "trace_id": "trace-id-example",
                            "result": {
                              "payment_id": 1,
                              "status": "COMPLETED",
                              "paid_at": "2025-01-15 10:00:00"
                            }
                          }
                          """))),
  @ApiResponse(
      responseCode = "400",
      description = "결제 금액 불일치",
      content =
          @Content(
              mediaType = "application/json",
              schema =
                  @Schema(implementation = com.ticketrush.global.dto.response.ApiResponse.class),
              examples =
                  @ExampleObject(
                      name = "AmountMismatch",
                      summary = "요청 금액과 PG 승인 금액 불일치",
                      value =
                          """
                          {
                            "is_success": false,
                            "code": "PAYMENT_400_001",
                            "message": "결제 금액이 일치하지 않습니다.",
                            "trace_id": "trace-id-example"
                          }
                          """))),
  @ApiResponse(
      responseCode = "502",
      description = "PG사 승인 실패",
      content =
          @Content(
              mediaType = "application/json",
              schema =
                  @Schema(implementation = com.ticketrush.global.dto.response.ApiResponse.class),
              examples =
                  @ExampleObject(
                      name = "ApprovalFailed",
                      summary = "PG사 결제 승인 실패",
                      value =
                          """
                          {
                            "is_success": false,
                            "code": "PAYMENT_502_001",
                            "message": "PG사 결제 승인에 실패했습니다.",
                            "trace_id": "trace-id-example"
                          }
                          """)))
})
public @interface PaymentConfirmApiResponses {}
