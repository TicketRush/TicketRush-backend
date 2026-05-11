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
      description = "잘못된 요청",
      content =
          @Content(
              mediaType = "application/json",
              schema =
                  @Schema(implementation = com.ticketrush.global.dto.response.ApiResponse.class),
              examples = {
                @ExampleObject(
                    name = "ValidationError",
                    summary = "필수값 누락 또는 유효성 검증 실패",
                    value =
                        """
                        {
                          "isSuccess": false,
                          "code": "VALID_400_001",
                          "message": "입력값이 올바르지 않습니다.",
                          "traceId": "trace-id-example"
                        }
                        """),
                @ExampleObject(
                    name = "MainImageMissing",
                    summary = "메인 이미지 누락",
                    value =
                        """
                        {
                          "isSuccess": false,
                          "code": "PERFORMANCE_400_001",
                          "message": "메인 이미지는 필수입니다.",
                          "traceId": "trace-id-example"
                        }
                        """),
                @ExampleObject(
                    name = "Model3dMissing",
                    summary = "3D 모델 파일 누락",
                    value =
                        """
                        {
                          "isSuccess": false,
                          "code": "PERFORMANCE_400_002",
                          "message": "3D 모델 파일은 필수입니다.",
                          "traceId": "trace-id-example"
                        }
                        """),
                @ExampleObject(
                    name = "GalleryLimitExceeded",
                    summary = "갤러리 이미지 3개 초과",
                    value =
                        """
                        {
                          "isSuccess": false,
                          "code": "PERFORMANCE_400_003",
                          "message": "갤러리 이미지는 최대 3개까지 업로드할 수 있습니다.",
                          "traceId": "trace-id-example"
                        }
                        """)
              })),
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
public @interface PerformanceCreateApiResponses {}
