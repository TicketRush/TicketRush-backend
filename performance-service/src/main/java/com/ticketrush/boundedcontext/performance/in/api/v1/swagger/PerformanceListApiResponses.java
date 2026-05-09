package com.ticketrush.boundedcontext.performance.in.api.v1.swagger;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
      description = "잘못된 요청",
      content =
          @Content(
              mediaType = "application/json",
              examples =
                  @ExampleObject(
                      name = "InvalidGenre",
                      summary = "유효하지 않은 장르 값",
                      value =
                          """
                          {
                            "isSuccess": false,
                            "code": "COMMON_400",
                            "message": "잘못된 요청입니다.",
                            "traceId": "trace-id-example"
                          }
                          """)))
})
public @interface PerformanceListApiResponses {}
