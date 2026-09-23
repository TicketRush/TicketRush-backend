package com.ticketrush.boundedcontext.booking.app.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TimeZone;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ResourceLock("java.util.TimeZone.default")
class BookingUtcResponseContractTest {

  static Stream<Arguments> responses() {
    return Stream.of(
        Arguments.of(
            "BookingSummaryResponse",
            (Function<LocalDateTime, Object>)
                time ->
                    new BookingSummaryResponse(
                        1L, "BK-1", 2L, 3L, 4L, BookingStatus.PENDING, time, time, time, time),
            List.of("confirmed_at", "refund_failed_at", "updated_at", "expires_at")),
        Arguments.of(
            "BookingMySummaryResponse",
            (Function<LocalDateTime, Object>)
                time ->
                    new BookingMySummaryResponse(
                        1L,
                        "BK-1",
                        2L,
                        3L,
                        4L,
                        BookingStatus.PENDING,
                        time,
                        time,
                        time,
                        time,
                        "공연",
                        java.time.LocalDate.of(2026, 12, 31),
                        java.time.LocalTime.of(19, 30),
                        "서울",
                        "A1",
                        1000L),
            List.of("confirmed_at", "refund_failed_at", "updated_at", "expires_at")),
        Arguments.of(
            "BookingDetailResponse",
            (Function<LocalDateTime, Object>)
                time ->
                    new BookingDetailResponse(
                        1L,
                        "BK-1",
                        BookingStatus.PENDING,
                        3L,
                        "공연",
                        java.time.LocalDate.of(2026, 12, 31),
                        java.time.LocalTime.of(19, 30),
                        "서울",
                        4L,
                        "A1",
                        time,
                        time,
                        1000L),
            List.of("confirmed_at", "expires_at")),
        Arguments.of(
            "BookingAdminSummaryResponse",
            (Function<LocalDateTime, Object>)
                time ->
                    new BookingAdminSummaryResponse(
                        1L,
                        "BK-1",
                        2L,
                        3L,
                        4L,
                        BookingStatus.PENDING,
                        time,
                        "공연",
                        java.time.LocalDate.of(2026, 12, 31),
                        "회원",
                        "a@b.com",
                        "A1",
                        1,
                        1000L),
            List.of("booked_at")));
  }

  @ParameterizedTest(name = "{0} 발생·만료 시각의 UTC 전송 계약")
  @MethodSource("responses")
  void responseTimestampsKeepUtcInstant(
      String name, Function<LocalDateTime, Object> response, List<String> fields) {
    TimeZone original = TimeZone.getDefault();
    LocalDateTime time = LocalDateTime.parse("2026-12-31T23:59:59.987654321");
    try {
      for (String zone : List.of("UTC", "Asia/Seoul")) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        assertThat(TimeZone.getDefault().getID()).isEqualTo(zone);
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonConfig().jacksonCustomizer().customize(builder);
        JsonMapper mapper = builder.build();
        JsonNode envelope =
            mapper.readTree(
                mapper.writeValueAsString(
                    ApiResponse.onSuccess(SuccessStatus.OK, response.apply(time)).getBody()));
        assertThat(envelope.get("is_success").asBoolean()).isTrue();
        assertThat(envelope.get("code").asText()).isEqualTo("COMMON_200");
        JsonNode json = envelope.get("result");
        if (json.has("performance_date")) {
          assertThat(json.get("performance_date").asText()).isEqualTo("2026-12-31");
        }
        if (json.has("performance_time")) {
          assertThat(json.get("performance_time").asText()).isEqualTo("19:30:00");
        }
        JsonNode empty = mapper.readTree(mapper.writeValueAsString(response.apply(null)));
        for (String field : fields) {
          String pointer = "/" + field.replace('.', '/');
          String actual = json.at(pointer).asText();
          assertThat(actual).as("%s %s %s", zone, name, field).isEqualTo("2026-12-31T23:59:59Z");
          assertThat(Instant.parse(actual))
              .isEqualTo(time.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
          assertThat(empty.at(pointer).isMissingNode()).as(field + " null 생략").isTrue();
        }
      }
    } finally {
      TimeZone.setDefault(original);
    }
  }
}
