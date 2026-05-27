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
      description = "결제 내역 단건 조회 성공",
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
                              "booking_id": 12,
                              "provider": "TOSS",
                              "amount": 55000,
                              "status": "COMPLETED",
                              "paid_at": "2025-01-15 10:00:00",
                              "payment_key": "tgen_xxx",
                              "approval_number": "00000001",
                              "refund": null
                            }
                          }
                          """))),
  @ApiResponse(
      responseCode = "404",
      description = "결제 내역 없음 또는 본인 결제가 아님",
      content =
          @Content(
              mediaType = "application/json",
              schema =
                  @Schema(implementation = com.ticketrush.global.dto.response.ApiResponse.class),
              examples =
                  @ExampleObject(
                      name = "NotFound",
                      summary = "결제 내역이 없거나 본인 결제가 아닐 때",
                      value =
                          """
                          {
                            "is_success": false,
                            "code": "PAYMENT_404_002",
                            "message": "결제 내역을 찾을 수 없습니다.",
                            "trace_id": "trace-id-example"
                          }
                          """)))
})
public @interface PaymentDetailApiResponses {}
