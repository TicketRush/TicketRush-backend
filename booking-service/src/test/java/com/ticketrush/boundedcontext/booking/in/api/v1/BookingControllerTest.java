package com.ticketrush.boundedcontext.booking.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingCountResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingDetailResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingMySummaryResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPendingResponse;
import com.ticketrush.boundedcontext.booking.app.facade.BookingFacade;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.global.status.ErrorStatus;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BookingController.class)
@Import({
  JacksonConfig.class,
  SecurityConfig.class,
  GatewayHeaderFilter.class,
  CustomSecurityProperties.class
})
@TestPropertySource(properties = "gateway.internal-token=test-token")
class BookingControllerTest {

  private static final String INTERNAL_TOKEN = "test-token";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private BookingFacade bookingFacade;

  @Test
  @DisplayName("인증 principal의 userId로 예매 생성을 요청한다")
  void createPendingBooking_uses_authenticated_user_id() throws Exception {
    // given
    Long userId = 1L;
    Long performanceId = 2L;
    Long seatId = 3L;
    BookingPendingResponse response = new BookingPendingResponse(100L, "BOOK-1234", "PENDING");

    given(bookingFacade.createBooking(userId, performanceId, seatId)).willReturn(response);

    String requestBody =
        """
        {
          "performance_id": 2,
          "seat_id": 3
        }
        """;

    // when & then
    mockMvc
        .perform(
            post("/api/v1/booking")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.booking_number").value("BOOK-1234"));

    verify(bookingFacade).createBooking(eq(userId), eq(performanceId), eq(seatId));
  }

  @Test
  @DisplayName("인증 principal의 userId로 내 예매 내역을 조회한다 — 공연·좌석·금액 보강 필드 포함")
  void getMyBookings_uses_authenticated_user_id() throws Exception {
    // given
    Long userId = 1L;
    BookingMySummaryResponse response =
        new BookingMySummaryResponse(
            100L,
            "BOOK-1234",
            userId,
            2L,
            3L,
            BookingStatus.CONFIRMED,
            LocalDateTime.of(2026, 5, 22, 10, 30),
            null,
            null,
            null,
            "오페라의 유령",
            LocalDate.of(2026, 5, 22),
            "서울 예술의전당 오페라극장",
            "A-1",
            150000L);

    given(
            bookingFacade.getMyBookings(
                userId, BookingStatus.CONFIRMED, new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 10), 1));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/me")
                .param("status", "CONFIRMED")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result[0].booking_number").value("BOOK-1234"))
        .andExpect(jsonPath("$.result[0].performance_id").value(2))
        .andExpect(jsonPath("$.result[0].seat_id").value(3))
        .andExpect(jsonPath("$.result[0].booking_status").value("CONFIRMED"))
        .andExpect(jsonPath("$.result[0].confirmed_at").value("2026-05-22 10:30:00"))
        .andExpect(jsonPath("$.result[0].performance_title").value("오페라의 유령"))
        .andExpect(jsonPath("$.result[0].performance_date").value("2026-05-22"))
        .andExpect(jsonPath("$.result[0].performance_address").value("서울 예술의전당 오페라극장"))
        .andExpect(jsonPath("$.result[0].seat_number").value("A-1"))
        .andExpect(jsonPath("$.result[0].payment_amount").value(150000))
        .andExpect(jsonPath("$.pagination_info.page_index").value(0))
        .andExpect(jsonPath("$.pagination_info.size").value(10))
        .andExpect(jsonPath("$.pagination_info.total_elements").value(1))
        .andExpect(jsonPath("$.pagination_info.total_pages").value(1));

    verify(bookingFacade)
        .getMyBookings(eq(userId), eq(BookingStatus.CONFIRMED), eq(new OffsetPageRequest(0, 10)));
  }

  @Test
  @DisplayName("인증 principal의 userId로 예매 단건을 조회한다 — 공연·좌석 보강 필드 포함")
  void getMyBooking_uses_authenticated_user_id() throws Exception {
    // given
    Long userId = 1L;
    String bookingNumber = "X7B29-KLPW1";
    BookingDetailResponse response =
        new BookingDetailResponse(
            100L,
            bookingNumber,
            BookingStatus.CONFIRMED,
            2L,
            "오페라의 유령",
            LocalDate.of(2026, 5, 22),
            LocalTime.of(19, 30),
            "서울 예술의전당 오페라극장",
            3L,
            "A-1",
            LocalDateTime.of(2026, 5, 22, 10, 30),
            null,
            150000L);

    given(bookingFacade.getMyBooking(userId, bookingNumber)).willReturn(response);

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/{bookingNumber}", bookingNumber)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.booking_id").value(100))
        .andExpect(jsonPath("$.result.booking_number").value(bookingNumber))
        .andExpect(jsonPath("$.result.booking_status").value("CONFIRMED"))
        .andExpect(jsonPath("$.result.performance_id").value(2))
        .andExpect(jsonPath("$.result.performance_title").value("오페라의 유령"))
        .andExpect(jsonPath("$.result.performance_date").value("2026-05-22"))
        .andExpect(jsonPath("$.result.performance_time").value("19:30:00"))
        .andExpect(jsonPath("$.result.performance_address").value("서울 예술의전당 오페라극장"))
        .andExpect(jsonPath("$.result.seat_id").value(3))
        .andExpect(jsonPath("$.result.seat_number").value("A-1"))
        .andExpect(jsonPath("$.result.confirmed_at").value("2026-05-22 10:30:00"))
        .andExpect(jsonPath("$.result.payment_amount").value(150000));

    verify(bookingFacade).getMyBooking(eq(userId), eq(bookingNumber));
  }

  @Test
  @DisplayName("부분 응답: 공연 조회가 실패해도 booking 코어 필드와 좌석 번호는 내려간다")
  void getMyBooking_returns_partial_response_when_performance_missing() throws Exception {
    // given
    Long userId = 1L;
    String bookingNumber = "X7B29-KLPW1";
    BookingDetailResponse response =
        new BookingDetailResponse(
            100L,
            bookingNumber,
            BookingStatus.CONFIRMED,
            2L,
            null,
            null,
            null,
            null,
            3L,
            "A-1",
            LocalDateTime.of(2026, 5, 22, 10, 30),
            null,
            null);

    given(bookingFacade.getMyBooking(userId, bookingNumber)).willReturn(response);

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/{bookingNumber}", bookingNumber)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.booking_id").value(100))
        .andExpect(jsonPath("$.result.performance_id").value(2))
        .andExpect(jsonPath("$.result.performance_title").doesNotExist())
        .andExpect(jsonPath("$.result.payment_amount").doesNotExist())
        .andExpect(jsonPath("$.result.seat_number").value("A-1"));
  }

  @Test
  @DisplayName("타인 예매·미존재 예매는 동일하게 404를 반환한다")
  void getMyBooking_returns_404_for_missing_or_others_booking() throws Exception {
    // given
    Long userId = 1L;
    String bookingNumber = "X7B29-KLPW1";
    given(bookingFacade.getMyBooking(userId, bookingNumber))
        .willThrow(new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/{bookingNumber}", bookingNumber)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.BOOKING_NOT_FOUND.getCode()));
  }

  @Test
  @DisplayName("인증 principal이 없으면 예매 단건 조회는 401 Unauthorized를 반환한다")
  void getMyBooking_fails_when_principal_missing() throws Exception {
    mockMvc
        .perform(get("/api/v1/booking/{bookingNumber}", "X7B29-KLPW1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(ErrorStatus.UNAUTHORIZED.getCode()));

    verifyNoInteractions(bookingFacade);
  }

  @Test
  @DisplayName("인증 principal이 없으면 내 예매 내역 조회는 401 Unauthorized를 반환한다 — 매처 확대(#560) 검증")
  void getMyBookings_fails_when_principal_missing() throws Exception {
    mockMvc
        .perform(get("/api/v1/booking/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(ErrorStatus.UNAUTHORIZED.getCode()));

    verifyNoInteractions(bookingFacade);
  }

  @Test
  @DisplayName("401 본문의 한글 메시지가 깨지지 않는다 — charset 미지정 시 ISO-8859-1로 나가 '?'가 된다")
  void unauthorized_response_body_is_utf8() throws Exception {
    byte[] body =
        mockMvc
            .perform(get("/api/v1/booking/me"))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    // jsonPath는 code(ASCII)만 봐서 이 회귀를 놓친다. 실제 바이트를 UTF-8로 읽어 메시지를 비교한다.
    assertThat(new String(body, StandardCharsets.UTF_8))
        .contains(ErrorStatus.UNAUTHORIZED.getMessage());
  }

  @Test
  @DisplayName("인증 principal의 userId로 내 예매 수를 조회한다")
  void countMyBookings_uses_authenticated_user_id() throws Exception {
    // given
    Long userId = 1L;
    BookingCountResponse response = new BookingCountResponse(BookingStatus.CONFIRMED, 3L);
    given(bookingFacade.countMyBookings(userId, BookingStatus.CONFIRMED)).willReturn(response);

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/me/count")
                .param("status", "CONFIRMED")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.booking_status").value("CONFIRMED"))
        .andExpect(jsonPath("$.result.count").value(3));

    verify(bookingFacade).countMyBookings(eq(userId), eq(BookingStatus.CONFIRMED));
  }

  @Test
  @DisplayName("인증 principal의 userId로 내 예매 취소를 요청한다")
  void cancelMyBooking_uses_authenticated_user_id() throws Exception {
    // given
    Long userId = 1L;
    String bookingNumber = "BOOK-1234";

    // when & then
    mockMvc
        .perform(
            delete("/api/v1/booking/{bookingNumber}", bookingNumber)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true));

    verify(bookingFacade).cancelMyBooking(eq(userId), eq(bookingNumber));
  }

  @Test
  @DisplayName("인증 principal이 없으면 401 Unauthorized를 반환한다")
  void createPendingBooking_fails_when_principal_missing() throws Exception {
    // given
    String requestBody =
        """
        {
          "performance_id": 2,
          "seat_id": 3
        }
        """;

    // when & then
    mockMvc
        .perform(
            post("/api/v1/booking").contentType(MediaType.APPLICATION_JSON).content(requestBody))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.UNAUTHORIZED.getCode()));

    verifyNoInteractions(bookingFacade);
  }
}
