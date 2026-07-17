package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceChangeStatusRequest;
import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.global.config.CustomSecurityProperties;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(PerformanceAdminController.class)
@Import(CustomSecurityProperties.class)
class PerformanceValidationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private PerformanceFacade performanceFacade;

  final String baseUrl = "/api/v1/performance/admin";

  private PerformanceCreateRequest.PerformanceCreateRequestBuilder createBaseRequest() {
    return PerformanceCreateRequest.builder()
        .title("정상 제목")
        .performer("정상 가수")
        .genre(Genre.CONCERT)
        .showDate(LocalDate.now())
        .showTime(LocalTime.of(19, 0))
        .durationMinutes(120)
        .price(50000L)
        .totalSeats(100)
        .address("서울시");
  }

  private ResultActions performMultipartRequest(PerformanceCreateRequest request) throws Exception {
    MockMultipartFile jsonPart =
        new MockMultipartFile(
            "request",
            "",
            MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8));

    MockMultipartFile mainImage =
        new MockMultipartFile("mainImage", "i.png", "image/png", "t".getBytes());
    MockMultipartFile model3d =
        new MockMultipartFile("model3d", "m.glb", "application/octet-stream", "t".getBytes());

    return mockMvc.perform(
        multipart(baseUrl).file(jsonPart).file(mainImage).file(model3d).with(csrf()));
  }

  @Test
  @WithMockUser
  @DisplayName("공연명이 비어있으면 에러가 발생한다")
  void titleNotBlankValidationFail() throws Exception {
    PerformanceCreateRequest request = createBaseRequest().title("").build();

    performMultipartRequest(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALID_400_001"))
        .andExpect(jsonPath("$.message").value(containsString("title")));
  }

  @Test
  @WithMockUser
  @DisplayName("가격이 음수이면 에러가 발생한다")
  void pricePositiveValidationFail() throws Exception {
    PerformanceCreateRequest request = createBaseRequest().price(-1000L).build();

    performMultipartRequest(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("price")));
  }

  @Test
  @WithMockUser
  @DisplayName("장르를 선택하지 않으면 에러가 발생한다")
  void genreNotNullValidationFail() throws Exception {
    PerformanceCreateRequest request = createBaseRequest().genre(null).build();

    performMultipartRequest(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALID_400_001"))
        .andExpect(jsonPath("$.message").value(containsString("genre")));
  }

  @Test
  @WithMockUser
  @DisplayName("공연장 주소가 비어있으면 에러가 발생한다")
  void addressNotBlankValidationFail() throws Exception {
    PerformanceCreateRequest request = createBaseRequest().address("").build();

    performMultipartRequest(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("address")));
  }

  @Test
  @WithMockUser
  @DisplayName("여러 필드가 동시에 유효하지 않으면 VALID401 에러가 발생한다")
  void complexValidationFail() throws Exception {
    PerformanceCreateRequest request =
        createBaseRequest().title("").price(-50000L).address("").build();

    performMultipartRequest(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALID_400_001"))
        .andExpect(jsonPath("$.message").value(containsString("title")))
        .andExpect(jsonPath("$.message").value(containsString("price")))
        .andExpect(jsonPath("$.message").value(containsString("address")));
  }

  @Test
  @WithMockUser
  @DisplayName("필수 필드가 누락되거나 잘못되면 VALID401 에러가 발생한다")
  void mandatoryFieldsValidationFail() throws Exception {
    PerformanceCreateRequest request =
        createBaseRequest().showDate(null).showTime(null).durationMinutes(0).totalSeats(-1).build();

    performMultipartRequest(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("showDate")))
        .andExpect(jsonPath("$.message").value(containsString("showTime")))
        .andExpect(jsonPath("$.message").value(containsString("durationMinutes")))
        .andExpect(jsonPath("$.message").value(containsString("totalSeats")));
  }

  @Test
  @WithMockUser
  @DisplayName("필수 문자열 필드에 공백(\" \")만 입력되면 VALID401 에러가 발생한다")
  void whiteSpaceValidationFail() throws Exception {
    PerformanceCreateRequest request = createBaseRequest().title("   ").address(" ").build();

    performMultipartRequest(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("title")))
        .andExpect(jsonPath("$.message").value(containsString("address")));
  }

  @Test
  @WithMockUser
  @DisplayName("모든 값이 올바르면 201 응답을 반환한다")
  void createPerformanceSuccess() throws Exception {
    PerformanceCreateRequest request = createBaseRequest().build();

    performMultipartRequest(request)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.isSuccess").value(true));
  }

  @Test
  @WithMockUser
  @DisplayName("status 필드가 누락되면 400 에러가 발생한다")
  void changeStatusValidationFail() throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.patch(baseUrl + "/1/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(csrf()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALID_400_001"));
  }

  @Test
  @WithMockUser
  @DisplayName("status 필드가 올바르면 200 응답을 반환한다")
  void changeStatusSuccess() throws Exception {
    PerformanceChangeStatusRequest request =
        new PerformanceChangeStatusRequest(PerformanceStatus.ON_SALE);

    mockMvc
        .perform(
            MockMvcRequestBuilders.patch(baseUrl + "/1/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isSuccess").value(true));
  }
}
