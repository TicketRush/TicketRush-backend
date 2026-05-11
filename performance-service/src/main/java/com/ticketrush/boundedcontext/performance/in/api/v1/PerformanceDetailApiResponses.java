package com.ticketrush.boundedcontext.performance.in.api.v1;

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
      responseCode = "200",
      description = "공연 상세 조회 성공",
      content =
          @Content(
              mediaType = "application/json",
              examples =
                  @ExampleObject(
                      name = "Success",
                      summary = "정상 응답",
                      value =
                          """
                          {
                            "isSuccess": true,
                            "code": "COMMON_200",
                            "message": "요청에 성공하였습니다.",
                            "result": {
                              "performanceId": 1,
                              "title": "레미제라블",
                              "performer": "홍길동",
                              "genre": "MUSICAL",
                              "description": "공연 안내 내용",
                              "showDate": "2025-09-01",
                              "showTime": "19:00:00",
                              "durationMinutes": 150,
                              "price": 80000,
                              "totalSeats": 500,
                              "address": "서울특별시 중구 세종대로 110",
                              "performanceStatus": "ON_SALE",
                              "imageMainUrl": "https://s3.example.com/main.jpg",
                              "image3dUrl": "https://s3.example.com/model.glb",
                              "imageGalleryUrls": [
                                "https://s3.example.com/gallery1.jpg",
                                "https://s3.example.com/gallery2.jpg"
                              ],
                              "facilities": ["주차장", "수유실", "장애인 편의시설"]
                            }
                          }
                          """))),
  @ApiResponse(
      responseCode = "404",
      description = "공연 없음",
      content =
          @Content(
              mediaType = "application/json",
              examples =
                  @ExampleObject(
                      name = "PerformanceNotFound",
                      summary = "존재하지 않는 공연 ID",
                      value =
                          """
                          {
                            "isSuccess": false,
                            "code": "PERFORMANCE_404_001",
                            "message": "공연이 존재하지 않습니다.",
                            "traceId": "trace-id-example"
                          }
                          """)))
})
public @interface PerformanceDetailApiResponses {}
