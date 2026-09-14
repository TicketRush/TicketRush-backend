package com.ticketrush.global.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.shared.booking.event.BookingExpiredEvent;
import com.ticketrush.shared.booking.event.RefundRequestedEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.json.JsonMapper;

@ResourceLock("java.util.TimeZone.default")
class UtcResponseContractTest {

  private JsonMapper mapper() {
    JsonMapper.Builder builder = JsonMapper.builder();
    new JacksonConfig().jacksonCustomizer().customize(builder);
    return builder.build();
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTC", "Asia/Seoul"})
  @DisplayName("응답 필드만 UTC 초 단위로 절삭하고 자정·월·연도 경계를 이동하지 않는다")
  void response_preserves_utc_instant_at_boundaries(String zone) {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      assertThat(TimeZone.getDefault().getID()).isEqualTo(zone);
      JsonMapper mapper = mapper();
      for (String source :
          new String[] {
            "2026-09-14T05:00:00.999999999",
            "2026-09-30T23:59:59.999999999",
            "2026-12-31T23:59:59.999999999",
            "2027-01-01T00:00:00"
          }) {
        LocalDateTime value = LocalDateTime.parse(source);
        String wire =
            mapper
                .readTree(mapper.writeValueAsString(new Response(value)))
                .get("created_at")
                .asText();
        assertThat(wire)
            .isEqualTo(
                value
                    .truncatedTo(ChronoUnit.SECONDS)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));
        assertThat(Instant.parse(wire))
            .isEqualTo(value.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
      }
      assertThat(mapper.writeValueAsString(new Response(null))).isEqualTo("{}");
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTC", "Asia/Seoul"})
  @DisplayName("기존 요청·전역 날짜·UTC 출처의 무시간대 이벤트 페이로드 계약을 유지한다")
  void legacy_requests_and_events_remain_naive(String zone) {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      JsonConverter converter = new JsonConverter(mapper());
      String request =
          "{\"booking_open_at\":\"2026-09-14 05:00:00\",\"show_date\":\"2026-09-14\","
              + "\"show_time\":\"14:00:00\"}";
      CalendarRequest parsed = converter.deserialize(request, CalendarRequest.class);
      assertThat(parsed.bookingOpenAt()).isEqualTo(LocalDateTime.of(2026, 9, 14, 5, 0));
      assertThat(parsed.showDate()).isEqualTo(LocalDate.of(2026, 9, 14));
      assertThat(parsed.showTime()).isEqualTo(LocalTime.of(14, 0));
      assertThat(converter.serialize(parsed)).isEqualTo(request);

      // UTC로 기록되었음을 알고 있는 합성 구형 데이터만 호환 대상으로 사용한다.
      String expired = "{\"booking_id\":1,\"expired_at\":\"2026-09-14 05:00:00\"}";
      BookingExpiredEvent event = converter.deserialize(expired, BookingExpiredEvent.class);
      assertThat(event.expiredAt().toInstant(ZoneOffset.UTC))
          .isEqualTo(Instant.parse("2026-09-14T05:00:00Z"));
      assertThat(converter.serialize(event)).isEqualTo(expired);
      String refund =
          "{\"booking_id\":1,\"booking_number\":\"B1\",\"seat_id\":2,\"user_id\":3,"
              + "\"requested_at\":\"2026-09-14 05:00:00\"}";
      RefundRequestedEvent refundEvent = converter.deserialize(refund, RefundRequestedEvent.class);
      assertThat(refundEvent.requestedAt()).isEqualTo(event.expiredAt());
      assertThat(converter.serialize(refundEvent)).isEqualTo(refund);
    } finally {
      TimeZone.setDefault(original);
    }
  }

  record Response(
      @JsonSerialize(using = UtcLocalDateTimeSerializer.class) LocalDateTime createdAt) {}

  record CalendarRequest(LocalDateTime bookingOpenAt, LocalDate showDate, LocalTime showTime) {}
}
