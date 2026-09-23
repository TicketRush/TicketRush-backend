package com.ticketrush.boundedcontext.performance.app.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.performance.domain.policy.PerformanceShowTimePolicy;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import com.ticketrush.global.types.PerformanceStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code booking_open_at}의 전송 계약(#671).
 *
 * <p>다른 서비스의 시각은 저장값이 이미 UTC라 {@code ...Z}로 나가지만, 이 필드는 저장값이 Asia/Seoul 벽시계라 숫자를 그대로 두고 오프셋만
 * 붙인다({@code +09:00}). 관리자 수정 화면이 이 응답을 폼에 되돌려 저장하는 왕복을 타기 때문이다(#650).
 *
 * <p>이 테스트는 직렬화 형식, 실제 순간, JVM 기본 시간대에 따른 영향을 함께 검증한다.
 */
@ResourceLock("java.util.TimeZone.default")
class PerformanceUtcResponseContractTest {

  private static final List<String> JVM_ZONES = List.of("UTC", "Asia/Seoul");

  private static final LocalDate SHOW_DATE = LocalDate.of(2027, 1, 10);

  private static final LocalTime SHOW_TIME = LocalTime.of(19, 0);

  static List<Arguments> bookingOpenAtCases() {
    return List.of(
        Arguments.of(LocalDateTime.parse("2026-09-22T19:00:00"), "2026-09-22T19:00:00+09:00"),
        Arguments.of(LocalDateTime.parse("2027-01-01T00:00:00"), "2027-01-01T00:00:00+09:00"),
        Arguments.of(
            LocalDateTime.parse("2026-12-31T23:59:59.987654321"), "2026-12-31T23:59:59+09:00"));
  }

  private static PerformanceDetailResponse response(LocalDateTime bookingOpenAt) {
    return new PerformanceDetailResponse(
        1L,
        "공연",
        "출연자",
        Genre.MUSICAL,
        "설명",
        SHOW_DATE,
        SHOW_TIME,
        PerformanceShowTimePolicy.showAt(SHOW_DATE, SHOW_TIME),
        120,
        50000L,
        100,
        "서울",
        PerformanceStatus.UPCOMING,
        bookingOpenAt,
        null,
        null,
        List.of(),
        List.of(),
        null,
        null,
        false,
        null);
  }

  private static JsonMapper mapper() {
    JsonMapper.Builder builder = JsonMapper.builder();

    new JacksonConfig().jacksonCustomizer().customize(builder);

    return builder.build();
  }

  static List<Arguments> cases() {
    return JVM_ZONES.stream()
        .flatMap(
            zone ->
                bookingOpenAtCases().stream()
                    .map(testCase -> Arguments.of(zone, testCase.get()[0], testCase.get()[1])))
        .toList();
  }

  @ParameterizedTest(name = "JVM {0} · KST {1} → {2}")
  @MethodSource("cases")
  void bookingOpenAtCarriesSeoulOffset(String zone, LocalDateTime bookingOpenAt, String expected) {
    TimeZone original = TimeZone.getDefault();

    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));

      assertThat(TimeZone.getDefault().getID()).isEqualTo(zone);

      JsonMapper mapper = mapper();

      JsonNode envelope =
          mapper.readTree(
              mapper.writeValueAsString(
                  ApiResponse.onSuccess(SuccessStatus.OK, response(bookingOpenAt)).getBody()));

      assertThat(envelope.get("is_success").asBoolean()).isTrue();

      String actual = envelope.at("/result/booking_open_at").asString();

      assertThat(actual).isEqualTo(expected);

      assertThat(OffsetDateTime.parse(actual).toInstant())
          .isEqualTo(
              ZonedDateTime.of(bookingOpenAt, PerformanceShowTimePolicy.SHOW_ZONE)
                  .toInstant()
                  .truncatedTo(ChronoUnit.SECONDS));

      assertThat(envelope.at("/result/show_at").asString()).isEqualTo("2027-01-10T19:00:00+09:00");

      assertThat(
              mapper
                  .readTree(mapper.writeValueAsString(response(null)))
                  .at("/booking_open_at")
                  .isMissingNode())
          .isTrue();
    } finally {
      TimeZone.setDefault(original);
    }
  }
}
