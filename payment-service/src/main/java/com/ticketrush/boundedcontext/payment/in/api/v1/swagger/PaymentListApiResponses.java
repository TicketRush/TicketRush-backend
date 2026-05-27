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
      description = "결제 내역 목록 조회 성공",
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
                            "pagination_info": {
                              "page_index": 0,
                              "size": 10,
                              "has_next": false,
                              "total_elements": 1,
                              "total_pages": 1
                            },
                            "result": [
                              {
                                "payment_id": 1,
                                "booking_id": 12,
                                "provider": "TOSS",
                                "amount": 55000,
                                "status": "COMPLETED",
                                "paid_at": "2025-01-15 10:00:00"
                              }
                            ]
                          }
                          """)))
})
public @interface PaymentListApiResponses {}
