package com.ticketrush.boundedcontext.booking.in.api.v1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminRefundSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingRefundStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.facade.BookingFacade;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.domain.types.RefundProcessStatus;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BookingAdminController.class)
@Import({
  JacksonConfig.class,
  SecurityConfig.class,
  GatewayHeaderFilter.class,
  CustomSecurityProperties.class
})
@TestPropertySource(properties = "gateway.internal-token=test-token")
class BookingAdminControllerTest {

  private static final String INTERNAL_TOKEN = "test-token";
  private static final String BOOKING_NUMBER = "BOOK-1234";
  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 7, 10, 12, 0);

  @Autowired private MockMvc mockMvc;

  @MockitoBean private BookingFacade bookingFacade;

  @Test
  @DisplayName("ADMIN이 환불 실패 예매를 조회하면 실패 시각이 함께 응답된다")
  void getRefundFailedBookings_returns_bookings_with_refund_failed_at() throws Exception {
    // given: 환불 실패는 별도 상태가 아니라 CONFIRMED + refundFailedAt으로 표현된다 (#391)
    BookingSummaryResponse response =
        new BookingSummaryResponse(
            100L,
            BOOKING_NUMBER,
            5L,
            2L,
            3L,
            BookingStatus.CONFIRMED,
            LocalDateTime.of(2026, 5, 22, 10, 30),
            FAILED_AT,
            FAILED_AT,
            null);

    given(bookingFacade.getRefundFailedBookings(new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 10), 1));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings/refund-failed")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result[0].booking_number").value(BOOKING_NUMBER))
        .andExpect(jsonPath("$.result[0].user_id").value(5))
        .andExpect(jsonPath("$.result[0].booking_status").value("CONFIRMED"))
        .andExpect(jsonPath("$.result[0].refund_failed_at").value("2026-07-10T12:00:00Z"))
        .andExpect(jsonPath("$.pagination_info.total_elements").value(1));

    verify(bookingFacade).getRefundFailedBookings(new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("ADMIN이 환불 고착 예매를 조회하면 REFUNDING 진입 시각(updatedAt)이 함께 응답된다")
  void getRefundingStuckBookings_returns_stuck_bookings_with_updated_at() throws Exception {
    // given: REFUNDING에서 임계 시간 이상 멈춘 고착 예매 (#397)
    LocalDateTime stuckSince = LocalDateTime.of(2026, 7, 13, 11, 0);
    BookingSummaryResponse response =
        new BookingSummaryResponse(
            100L,
            BOOKING_NUMBER,
            5L,
            2L,
            3L,
            BookingStatus.REFUNDING,
            LocalDateTime.of(2026, 5, 22, 10, 30),
            null,
            stuckSince,
            null);

    given(bookingFacade.getRefundingStuckBookings(new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 10), 1));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings/refunding-stuck")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result[0].booking_number").value(BOOKING_NUMBER))
        .andExpect(jsonPath("$.result[0].booking_status").value("REFUNDING"))
        .andExpect(jsonPath("$.result[0].updated_at").value("2026-07-13T11:00:00Z"))
        .andExpect(jsonPath("$.pagination_info.total_elements").value(1));

    verify(bookingFacade).getRefundingStuckBookings(new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("ADMIN이 환불 재시도를 요청하면 facade로 위임된다")
  void retryRefund_delegates_to_facade() throws Exception {
    // when & then
    mockMvc
        .perform(
            post("/api/v1/booking/admin/{bookingNumber}/refund-retry", BOOKING_NUMBER)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true));

    verify(bookingFacade).retryRefund(1L, BOOKING_NUMBER);
  }

  @Test
  @DisplayName("ADMIN이 아닌 사용자는 관리자 API에 접근할 수 없다")
  void adminApi_is_forbidden_for_non_admin() throws Exception {
    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings/refund-failed")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(bookingFacade);
  }

  @Test
  @DisplayName("ADMIN이 전체 예매 목록을 조회하면 공연·예매자·좌석 보강 필드가 함께 응답된다")
  void getBookings_returns_enriched_bookings() throws Exception {
    // given
    BookingAdminSummaryResponse response =
        new BookingAdminSummaryResponse(
            100L,
            BOOKING_NUMBER,
            5L,
            2L,
            3L,
            BookingStatus.CONFIRMED,
            LocalDateTime.of(2026, 5, 22, 10, 30),
            "오페라의 유령",
            LocalDate.of(2026, 5, 22),
            "김소희",
            "user@example.com",
            "A-1",
            1,
            150000L);

    given(bookingFacade.getAdminBookings(1L, Set.of(), new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 10), 1));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result[0].booking_number").value(BOOKING_NUMBER))
        .andExpect(jsonPath("$.result[0].booked_at").value("2026-05-22T10:30:00Z"))
        .andExpect(jsonPath("$.result[0].performance_title").value("오페라의 유령"))
        .andExpect(jsonPath("$.result[0].performance_date").value("2026-05-22"))
        .andExpect(jsonPath("$.result[0].booker_name").value("김소희"))
        .andExpect(jsonPath("$.result[0].booker_email").value("user@example.com"))
        .andExpect(jsonPath("$.result[0].seat_number").value("A-1"))
        .andExpect(jsonPath("$.result[0].seat_count").value(1))
        .andExpect(jsonPath("$.result[0].payment_amount").value(150000))
        .andExpect(jsonPath("$.pagination_info.total_elements").value(1));

    verify(bookingFacade).getAdminBookings(1L, Set.of(), new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("ADMIN이 status를 지정하면 그 상태로 필터링된 목록이 응답된다 (#667)")
  void getBookings_passes_status_filter() throws Exception {
    // given
    given(
            bookingFacade.getAdminBookings(
                1L, Set.of(BookingStatus.REFUNDED), new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings")
                .param("status", "REFUNDED")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk());

    verify(bookingFacade)
        .getAdminBookings(1L, Set.of(BookingStatus.REFUNDED), new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("ADMIN이 status를 반복하면 중복을 제거한 상태 합집합을 전달한다 (#674)")
  void getBookings_passes_distinct_status_union() throws Exception {
    // given
    Set<BookingStatus> statuses = Set.of(BookingStatus.PENDING, BookingStatus.REFUNDING);
    given(bookingFacade.getAdminBookings(1L, statuses, new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings")
                .param("status", "PENDING", "REFUNDING", "PENDING")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk());

    verify(bookingFacade).getAdminBookings(1L, statuses, new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("복수 status 중 하나라도 정의되지 않은 값이면 요청 전체를 400으로 거절한다 (#674)")
  void getBookings_rejects_request_containing_unknown_status() throws Exception {
    // when & then: 상태코드만 보면 전역 핸들러가 빠져도 Spring 기본 resolver가 400을 내 통과한다.
    // 핸들러를 실제로 경유했는지는 ApiResponse 엔벨로프로만 구분되므로 code까지 고정한다.
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings")
                .param("status", "PENDING", "NOPE")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.BAD_REQUEST.getCode()));

    verifyNoInteractions(bookingFacade);
  }

  @Test
  @DisplayName("status를 빈 값으로 보내면 400이 아니라 전체 조회로 떨어진다 (#667)")
  void getBookings_treats_blank_status_as_no_filter() throws Exception {
    // given: 빈 문자열은 Spring의 enum 컨버터가 null로 바꾼다 — 미지정과 같은 경로다.
    // 400을 기대하기 쉬운 지점이라 계약으로 고정해 둔다.
    given(bookingFacade.getAdminBookings(1L, Set.of(), new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings")
                .param("status", "")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk());

    verify(bookingFacade).getAdminBookings(1L, Set.of(), new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("빈 status가 유효값과 섞이면 빈 값만 무시한다 (#674)")
  void getBookings_ignores_blank_status_mixed_with_valid_status() throws Exception {
    // given
    given(
            bookingFacade.getAdminBookings(
                1L, Set.of(BookingStatus.PENDING), new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings")
                .param("status", "", "PENDING", "")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk());

    verify(bookingFacade)
        .getAdminBookings(1L, Set.of(BookingStatus.PENDING), new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("status는 대소문자를 구분해 복수 값 중 소문자가 있으면 400이다 (#674)")
  void getBookings_rejects_request_containing_lowercase_status() throws Exception {
    // when & then: 프론트가 소문자로 보내면 배포 후에야 드러나므로 계약으로 고정한다
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings")
                .param("status", "PENDING", "refunded")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(bookingFacade);
  }

  @Test
  @DisplayName("페이지 범위 보정은 복수 status에도 동일하게 적용된다 (#674)")
  void getBookings_normalizes_page_range_with_multiple_statuses() throws Exception {
    // given
    Set<BookingStatus> statuses = Set.of(BookingStatus.PENDING, BookingStatus.REFUNDING);
    given(bookingFacade.getAdminBookings(1L, statuses, new OffsetPageRequest(0, 50)))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings")
                .param("status", "PENDING", "REFUNDING")
                .param("page", "-1")
                .param("size", "100")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk());

    verify(bookingFacade).getAdminBookings(1L, statuses, new OffsetPageRequest(0, 50));
  }

  @Test
  @DisplayName("ADMIN이 예매 번호로 단건을 조회하면 목록과 같은 보강 필드가 응답된다")
  void getBooking_returns_enriched_booking() throws Exception {
    // given: 좌석 관리자 화면이 좌석의 booking_number로 예매자를 조합하는 창구 (#562)
    BookingAdminSummaryResponse response =
        new BookingAdminSummaryResponse(
            100L,
            BOOKING_NUMBER,
            5L,
            2L,
            3L,
            BookingStatus.PENDING,
            LocalDateTime.of(2026, 5, 22, 10, 30),
            "오페라의 유령",
            LocalDate.of(2026, 5, 22),
            "김소희",
            "user@example.com",
            "A-1",
            1,
            null);

    given(bookingFacade.getAdminBooking(42L, BOOKING_NUMBER)).willReturn(response);

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings/{bookingNumber}", BOOKING_NUMBER)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 42L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.booking_number").value(BOOKING_NUMBER))
        .andExpect(jsonPath("$.result.seat_id").value(3))
        .andExpect(jsonPath("$.result.booking_status").value("PENDING"))
        .andExpect(jsonPath("$.result.booker_name").value("김소희"))
        .andExpect(jsonPath("$.result.seat_number").value("A-1"));

    verify(bookingFacade).getAdminBooking(42L, BOOKING_NUMBER);
  }

  @Test
  @DisplayName("존재하지 않는 예매 번호로 단건을 조회하면 404로 거절된다")
  void getBooking_returns_404_for_unknown_booking_number() throws Exception {
    // given
    given(bookingFacade.getAdminBooking(1L, "NOPE-0000"))
        .willThrow(new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings/{bookingNumber}", "NOPE-0000")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.BOOKING_NOT_FOUND.getCode()));
  }

  @Test
  @DisplayName("정적 경로가 단건 조회의 경로 변수보다 우선한다")
  void staticPaths_take_precedence_over_booking_number_variable() throws Exception {
    // given: /bookings/{bookingNumber}가 추가되면서 stats·refund-failed·refunding-stuck을 삼킬 수 있다.
    // Spring의 리터럴 우선 매칭에 의존하고 있으므로 그 전제를 여기서 고정한다 (#562).
    given(bookingFacade.getAdminBookingStats())
        .willReturn(new BookingAdminStatsResponse(0, 0, 0, 0L, true, 0));
    given(bookingFacade.getRefundFailedBookings(new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
    given(bookingFacade.getRefundingStuckBookings(new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

    // when & then
    for (String staticPath : List.of("stats", "refund-failed", "refunding-stuck")) {
      mockMvc
          .perform(
              get("/api/v1/booking/admin/bookings/" + staticPath)
                  .header("X-Gateway-Token", INTERNAL_TOKEN)
                  .header("X-User-Id", 1L)
                  .header("X-User-Role", "ADMIN"))
          .andExpect(status().isOk());
    }

    verify(bookingFacade).getAdminBookingStats();
    verify(bookingFacade).getRefundFailedBookings(new OffsetPageRequest(0, 10));
    verify(bookingFacade).getRefundingStuckBookings(new OffsetPageRequest(0, 10));
    verify(bookingFacade, never()).getAdminBooking(anyLong(), anyString());
  }

  @Test
  @DisplayName("ADMIN이 요약 통계를 조회하면 카운트 3종과 총 매출이 응답된다")
  void getBookingStats_returns_summary() throws Exception {
    // given
    given(bookingFacade.getAdminBookingStats())
        .willReturn(new BookingAdminStatsResponse(1250, 980, 120, 147000000L, true, 0));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings/stats")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.total_bookings").value(1250))
        .andExpect(jsonPath("$.result.completed_bookings").value(980))
        .andExpect(jsonPath("$.result.canceled_bookings").value(120))
        .andExpect(jsonPath("$.result.total_revenue").value(147000000L))
        .andExpect(jsonPath("$.result.revenue_complete").value(true));
  }

  @Test
  @DisplayName("결제 금액이 비어 있는 확정 예매가 있으면 매출이 불완전함을 함께 알린다")
  void getBookingStats_flags_incomplete_revenue() throws Exception {
    // given: 백필되지 않은 과거 확정 예매가 남아 있어 매출이 실제보다 작다 (#561)
    given(bookingFacade.getAdminBookingStats())
        .willReturn(new BookingAdminStatsResponse(1250, 980, 120, 146000000L, false, 7));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings/stats")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.completed_bookings").value(980))
        .andExpect(jsonPath("$.result.total_revenue").value(146000000L))
        .andExpect(jsonPath("$.result.revenue_complete").value(false))
        .andExpect(jsonPath("$.result.missing_amount_bookings").value(7));
  }

  @Test
  @DisplayName("ADMIN이 환불 처리를 요청하면 처리자 ID와 함께 파사드로 위임된다")
  void refundBooking_delegates_to_facade_with_admin_id() throws Exception {
    // when & then
    mockMvc
        .perform(
            post("/api/v1/booking/admin/{bookingNumber}/refund", BOOKING_NUMBER)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 42L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true));

    verify(bookingFacade).refundBooking(42L, BOOKING_NUMBER);
  }

  @Test
  @DisplayName("ADMIN이 아닌 사용자는 신규 관리자 API 세 경로에도 접근할 수 없다")
  void newAdminApis_are_forbidden_for_non_admin() throws Exception {
    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings/stats")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            post("/api/v1/booking/admin/{bookingNumber}/refund", BOOKING_NUMBER)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings/{bookingNumber}", BOOKING_NUMBER)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(bookingFacade);
  }

  @Test
  @DisplayName("ADMIN이 환불 통합 목록을 조회하면 예매 상태와 파생 환불 처리 상태가 함께 응답된다 (#675)")
  void getRefunds_returns_enriched_refunds() throws Exception {
    // given: 재시도 중인 건이다 — 실패 이력이 남아 있어도 현재 상태(REFUNDING)로 분류돼야 한다.
    // refund_failed_at으로 분류하면 이 행이 실패로 잡히는데, 그 회귀를 응답 계약으로 고정한다.
    BookingAdminRefundSummaryResponse response =
        new BookingAdminRefundSummaryResponse(
            100L,
            BOOKING_NUMBER,
            5L,
            2L,
            BookingStatus.REFUNDING,
            RefundProcessStatus.IN_PROGRESS,
            LocalDateTime.of(2026, 5, 22, 10, 30),
            FAILED_AT,
            "오페라의 유령",
            LocalDate.of(2026, 5, 22),
            LocalTime.of(19, 30),
            "김소희",
            150000L);

    given(bookingFacade.getAdminRefunds(1L, null, new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 10), 1));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/refunds")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result[0].booking_number").value(BOOKING_NUMBER))
        .andExpect(jsonPath("$.result[0].booking_status").value("REFUNDING"))
        .andExpect(jsonPath("$.result[0].refund_status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.result[0].refund_failed_at").value("2026-07-10T12:00:00Z"))
        .andExpect(jsonPath("$.result[0].booked_at").value("2026-05-22T10:30:00Z"))
        .andExpect(jsonPath("$.result[0].performance_title").value("오페라의 유령"))
        .andExpect(jsonPath("$.result[0].performance_date").value("2026-05-22"))
        .andExpect(jsonPath("$.result[0].performance_time").value("19:30:00"))
        .andExpect(jsonPath("$.result[0].booker_name").value("김소희"))
        .andExpect(jsonPath("$.result[0].payment_amount").value(150000L))
        .andExpect(jsonPath("$.pagination_info.total_elements").value(1));

    verify(bookingFacade).getAdminRefunds(1L, null, new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("refund_status를 지정하면 그대로 파사드에 전달된다 (#675)")
  void getRefunds_passes_refund_status_filter() throws Exception {
    // given
    given(
            bookingFacade.getAdminRefunds(
                1L, RefundProcessStatus.FAILED, new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/refunds")
                .param("refund_status", "FAILED")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk());

    verify(bookingFacade)
        .getAdminRefunds(1L, RefundProcessStatus.FAILED, new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("refund_status가 빈 값이면 400이 아니라 전체 조회로 떨어진다 (#675)")
  void getRefunds_treats_blank_refund_status_as_no_filter() throws Exception {
    // given: 빈 문자열은 Spring의 enum 컨버터가 null로 바꾼다 — 미지정과 같은 경로다.
    // 기존 status 파라미터와 같은 계약이라는 것을 여기서 고정한다.
    given(bookingFacade.getAdminRefunds(1L, null, new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/refunds")
                .param("refund_status", "")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk());

    verify(bookingFacade).getAdminRefunds(1L, null, new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("정의되지 않은 refund_status는 400으로 거절한다 (#675)")
  void getRefunds_rejects_unknown_refund_status() throws Exception {
    // when & then: REFUNDING은 BookingStatus의 값이지 RefundProcessStatus의 값이 아니다.
    // 두 enum을 혼동한 요청이 조용히 전체 조회로 떨어지지 않게 고정한다. 전역 핸들러를 실제로
    // 경유했는지는 ApiResponse 엔벨로프로만 구분되므로 code까지 본다.
    mockMvc
        .perform(
            get("/api/v1/booking/admin/refunds")
                .param("refund_status", "REFUNDING")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.BAD_REQUEST.getCode()));

    verifyNoInteractions(bookingFacade);
  }

  @Test
  @DisplayName("환불 목록도 page/size 범위를 보정한다 (#675)")
  void getRefunds_normalizes_page_range() throws Exception {
    // given
    given(bookingFacade.getAdminRefunds(1L, null, new OffsetPageRequest(0, 50)))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/refunds")
                .param("page", "-1")
                .param("size", "100")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk());

    verify(bookingFacade).getAdminRefunds(1L, null, new OffsetPageRequest(0, 50));
  }

  @Test
  @DisplayName("ADMIN이 환불 요약 통계를 조회하면 전체·완료 건수가 응답된다 (#675)")
  void getRefundStats_returns_summary() throws Exception {
    // given
    given(bookingFacade.getAdminRefundStats())
        .willReturn(new BookingRefundStatsResponse(312, 12, 280, 20));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/refunds/stats")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.total_refunds").value(312))
        .andExpect(jsonPath("$.result.in_progress_refunds").value(12))
        .andExpect(jsonPath("$.result.completed_refunds").value(280))
        .andExpect(jsonPath("$.result.failed_refunds").value(20));

    verify(bookingFacade).getAdminRefundStats();
  }

  @Test
  @DisplayName("환불 통계 경로가 환불 목록에 삼켜지지 않는다 (#675)")
  void refundStatsPath_is_not_swallowed_by_refund_list() throws Exception {
    // given: /refunds와 /refunds/stats는 한 세그먼트 차이라 매핑이 흔들리면 조용히 목록이 응답된다.
    // 두 경로가 서로 다른 파사드 메서드로 간다는 것을 고정한다.
    given(bookingFacade.getAdminRefunds(1L, null, new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
    given(bookingFacade.getAdminRefundStats())
        .willReturn(new BookingRefundStatsResponse(0, 0, 0, 0));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/refunds/stats")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.total_refunds").value(0));

    verify(bookingFacade).getAdminRefundStats();
    verify(bookingFacade, never()).getAdminRefunds(anyLong(), any(), any());
  }

  @Test
  @DisplayName("환불 조회 API는 ADMIN이 아니면 403이다 (#675)")
  void refundApis_are_forbidden_for_non_admin() throws Exception {
    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/refunds")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            get("/api/v1/booking/admin/refunds/stats")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(bookingFacade);
  }
}
