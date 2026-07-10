package com.ticketrush.boundedcontext.ticket.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ticketrush.boundedcontext.ticket.out.apiclient.dto.BookingInfoResponse;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BookingRestClientTest {

  private static final String BASE_URL = "http://localhost:8084";
  private static final String INTERNAL_TOKEN = "test-token";
  private static final long BOOKING_ID = 100L;
  private static final String REQUEST_URL = BASE_URL + "/api/v1/internal/booking/" + BOOKING_ID;

  private MockRestServiceServer mockServer;
  private BookingRestClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    mockServer = MockRestServiceServer.bindTo(builder).build();
    client = new BookingRestClient(builder.build());
    ReflectionTestUtils.setField(client, "internalToken", INTERNAL_TOKEN);
  }

  @Test
  @DisplayName("성공: 공통 ApiResponse 래퍼의 snake_case 본문을 역직렬화하고 X-Internal-Token을 전송한다")
  void getBooking_deserializes_snake_case_and_sends_token() {
    String responseBody =
        """
        {
          "is_success": true,
          "code": "COMMON_200",
          "message": "성공입니다.",
          "result": {
            "booking_id": 100,
            "user_id": 10,
            "booking_status": "CONFIRMED"
          }
        }
        """;

    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    BookingInfoResponse result = client.getBooking(BOOKING_ID);

    assertThat(result.bookingId()).isEqualTo(100L);
    assertThat(result.userId()).isEqualTo(10L);
    assertThat(result.bookingStatus()).isEqualTo("CONFIRMED");
    mockServer.verify();
  }

  @Test
  @DisplayName("실패: 예매 없음(404)은 TICKET_NOT_FOUND로 통일한다")
  void getBooking_maps_404_to_not_found() {
    mockServer.expect(requestTo(REQUEST_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_FOUND);
    mockServer.verify();
  }

  @Test
  @DisplayName("실패: 토큰 불일치(403)는 사용자에게 500을 노출하지 않고 통신 실패로 매핑한다")
  void getBooking_maps_403_to_communication_failed() {
    mockServer.expect(requestTo(REQUEST_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.TICKET_BOOKING_COMMUNICATION_FAILED);
    mockServer.verify();
  }

  @Test
  @DisplayName("실패: booking-service 5xx는 통신 실패로 매핑한다")
  void getBooking_maps_5xx_to_communication_failed() {
    mockServer.expect(requestTo(REQUEST_URL)).andRespond(withServerError());

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.TICKET_BOOKING_COMMUNICATION_FAILED);
    mockServer.verify();
  }

  @Test
  @DisplayName("실패: 200이지만 result 본문이 비어 있으면 통신 실패로 매핑한다")
  void getBooking_maps_empty_result_to_communication_failed() {
    String responseBody =
        """
        {
          "is_success": true,
          "code": "COMMON_200",
          "message": "성공입니다.",
          "result": null
        }
        """;

    mockServer
        .expect(requestTo(REQUEST_URL))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.TICKET_BOOKING_COMMUNICATION_FAILED);
    mockServer.verify();
  }
}
