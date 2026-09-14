package com.ticketrush.boundedcontext.seat.app.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.types.SeatStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ResourceLock("java.util.TimeZone.default")
class SeatUtcResponseTest {

  @ParameterizedTest
  @ValueSource(strings = {"UTC", "Asia/Seoul"})
  void allSeatResponseDates_useUtcSecondsRegardlessOfDefaultZone(String zone) {
    TimeZone previous = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      assertThat(TimeZone.getDefault().getID()).isEqualTo(zone);
      JsonMapper.Builder builder = JsonMapper.builder();
      new JacksonConfig().jacksonCustomizer().customize(builder);
      JsonMapper mapper = builder.build();
      LocalDateTime expiry = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 987654321);
      List<Object> responses =
          List.of(
              new SeatMapItemResponse(1L, 2L, "A-1", SeatStatus.HOLD, expiry),
              new SeatStatusChangedResponse(3L, 1L, 2L, "A-1", SeatStatus.HOLD, expiry),
              new SeatAdminSeatDetailResponse(
                  1L, "A-1", SeatStatus.HOLD, "BOOK-646", expiry.minusMinutes(5), expiry, 300));
      for (Object response : responses) {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));
        assertUtc(json.get("hold_expired_at").asString(), expiry);
        assertThat(json.has("seat_id")).isTrue();
        if (response instanceof SeatAdminSeatDetailResponse) {
          assertUtc(json.get("hold_started_at").asString(), expiry.minusMinutes(5));
        }
      }
      for (Object response :
          List.of(
              new SeatMapItemResponse(1L, 2L, "A-1", SeatStatus.AVAILABLE, null),
              new SeatStatusChangedResponse(3L, 1L, 2L, "A-1", SeatStatus.SOLD, null),
              new SeatAdminSeatDetailResponse(
                  1L, "A-1", SeatStatus.AVAILABLE, null, null, null, 0))) {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));
        assertThat(json.has("hold_expired_at")).isFalse();
        assertThat(json.has("hold_started_at")).isFalse();
      }
    } finally {
      TimeZone.setDefault(previous);
    }
  }

  private void assertUtc(String actual, LocalDateTime source) {
    assertThat(actual).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    assertThat(Instant.parse(actual))
        .isEqualTo(source.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
  }
}
