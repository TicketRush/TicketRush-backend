package com.ticketrush.boundedcontext.payment.out.apiclient.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * booking-service 응답과의 키 계약을 고정한다 (#607).
 *
 * <p>이 DTO 는 {@code ignoreUnknown = true} 라 키 이름이 어긋나도 예외가 나지 않고 <b>조용히 null</b> 이 된다. 그 null 은
 * {@code bookingNumber} 에서 특히 위험한데, 호출자가 그것을 "검증 생략"으로 새기면 남의 좌석을 푸는 환불이 나가기 때문이다(#608). 그래서 매핑 자체를
 * 테스트로 못박는다.
 */
class BookingInfoResponseTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("성공: snake_case 응답의 booking_number 를 매핑한다")
  void deserializes_booking_number() throws Exception {
    // given
    String json =
        """
        {"booking_id":100,"user_id":10,"booking_status":"EXPIRED","booking_number":"BOOK-1"}
        """;

    // when
    BookingInfoResponse response = objectMapper.readValue(json, BookingInfoResponse.class);

    // then
    assertThat(response.bookingId()).isEqualTo(100L);
    assertThat(response.userId()).isEqualTo(10L);
    assertThat(response.bookingStatus()).isEqualTo("EXPIRED");
    assertThat(response.bookingNumber()).isEqualTo("BOOK-1");
  }

  @Test
  @DisplayName("성공: booking_number 키가 없으면 null 이 된다(배포 순서 역전 구간)")
  void maps_missing_booking_number_to_null() throws Exception {
    // given — payment 가 booking 보다 먼저 배포되면 이 형태의 응답이 온다
    String json =
        """
        {"booking_id":100,"user_id":10,"booking_status":"EXPIRED"}
        """;

    // when
    BookingInfoResponse response = objectMapper.readValue(json, BookingInfoResponse.class);

    // then — 예외가 아니라 null 이므로, 환불 여부를 판정하는 쪽이 이 값을 반드시 확인해야 한다
    assertThat(response.bookingNumber()).isNull();
    assertThat(response.bookingStatus()).isEqualTo("EXPIRED");
  }

  @Test
  @DisplayName("성공: 모르는 키가 있어도 실패하지 않는다")
  void ignores_unknown_fields() throws Exception {
    // given
    String json =
        """
        {"booking_id":100,"user_id":10,"booking_status":"EXPIRED","booking_number":"BOOK-1","unknown":1}
        """;

    // when
    BookingInfoResponse response = objectMapper.readValue(json, BookingInfoResponse.class);

    // then
    assertThat(response.bookingNumber()).isEqualTo("BOOK-1");
  }
}
