package com.ticketrush.boundedcontext.performance.app.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.performance.domain.policy.PerformanceShowTimePolicy;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code booking_open_at}의 UTC 전송 계약 (#671).
 *
 * <p>형제 테스트({@code PaymentUtcResponseContractTest} 등)와 같은 꼴이지만 <b>기대 순간을 만드는 방식이 다르다</b>. 다른 서비스의
 * 시각은 저장값이 이미 UTC라 {@code toInstant(UTC)}로 비교하지만, 이 필드는 저장값이 Asia/Seoul 벽시계라(ADR 0020) 어드민이 입력한 그
 * 순간은 {@code ZonedDateTime.of(value, SHOW_ZONE)}로만 나온다. 이 테스트가 잡는 회귀가 정확히 그 혼동이다 — 값에 {@code Z}만
 * 붙이면 9시간 어긋난 순간을 가리키면서도 형식은 정상으로 보인다.
 */
@ResourceLock("java.util.TimeZone.default")
class PerformanceUtcResponseContractTest {

  static List<Arguments> bookingOpenAtCases() {
    return List.of(
        // 이슈 #671의 재현 값. KST 19:00 정각이 UTC 같은 날 10:00 이다.
        Arguments.of(LocalDateTime.parse("2026-09-22T19:00:00"), "2026-09-22T10:00:00Z"),
        // 자정 이전 KST 는 UTC 로 전날이 된다 — 날짜까지 바뀌는 자리를 고정한다.
        Arguments.of(LocalDateTime.parse("2027-01-01T00:00:00"), "2026-12-31T15:00:00Z"),
        // 초 미만 정밀도는 절삭한다(부모 직렬화기의 규칙).
        Arguments.of(LocalDateTime.parse("2026-12-31T23:59:59.987654321"), "2026-12-31T14:59:59Z"));
  }

  private static PerformanceDetailResponse response(LocalDateTime bookingOpenAt) {
    return new PerformanceDetailResponse(
        1L,
        "공연",
        "출연자",
        Genre.MUSICAL,
        "설명",
        LocalDate.of(2027, 1, 10),
        LocalTime.of(19, 0),
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
        null);
  }

  private static JsonMapper mapper() {
    JsonMapper.Builder builder = JsonMapper.builder();
    new JacksonConfig().jacksonCustomizer().customize(builder);
    return builder.build();
  }

  @ParameterizedTest(name = "KST {0} → {1}")
  @MethodSource("bookingOpenAtCases")
  @DisplayName("성공: booking_open_at 은 JVM 기본 존과 무관하게 같은 UTC 문자열로 나간다 (#671)")
  void bookingOpenAtKeepsSeoulInstantAsUtc(LocalDateTime bookingOpenAt, String expected) {
    TimeZone original = TimeZone.getDefault();
    try {
      for (String zone : List.of("UTC", "Asia/Seoul")) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        assertThat(TimeZone.getDefault().getID()).isEqualTo(zone);

        JsonMapper mapper = mapper();
        JsonNode envelope =
            mapper.readTree(
                mapper.writeValueAsString(
                    ApiResponse.onSuccess(SuccessStatus.OK, response(bookingOpenAt)).getBody()));

        assertThat(envelope.get("is_success").asBoolean()).isTrue();
        String actual = envelope.at("/result/booking_open_at").asString();

        // 형식 — 존 표시가 붙고, JVM 기본 존을 바꿔도 문자열이 같다.
        assertThat(actual).as("%s 존에서의 응답 문자열", zone).isEqualTo(expected);

        // 의미 — 그 문자열이 어드민이 Asia/Seoul 로 입력한 바로 그 순간을 가리킨다.
        assertThat(Instant.parse(actual))
            .as("%s 존에서의 순간", zone)
            .isEqualTo(
                ZonedDateTime.of(bookingOpenAt, PerformanceShowTimePolicy.SHOW_ZONE)
                    .toInstant()
                    .truncatedTo(ChronoUnit.SECONDS));
      }
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @ParameterizedTest(name = "JVM 기본 존 {0}")
  @MethodSource("zones")
  @DisplayName("성공: 오픈 시각이 없으면 booking_open_at 키가 빠진다 (#671)")
  void bookingOpenAtIsOmittedWhenNull(String zone) {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      JsonMapper mapper = mapper();

      JsonNode json = mapper.readTree(mapper.writeValueAsString(response(null)));

      assertThat(json.at("/booking_open_at").isMissingNode()).isTrue();
    } finally {
      TimeZone.setDefault(original);
    }
  }

  static List<Arguments> zones() {
    return List.of(Arguments.of("UTC"), Arguments.of("Asia/Seoul"));
  }
}
