package com.ticketrush.boundedcontext.performance.app.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.performance.domain.policy.PerformanceShowTimePolicy;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
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
 * {@code booking_open_at}의 전송 계약 (#671).
 *
 * <p>형제 테스트({@code PaymentUtcResponseContractTest} 등)와 같은 꼴이지만 <b>고정하는 계약이 다르다</b>. 다른 서비스의 시각은
 * 저장값이 이미 UTC 라 {@code ...Z}로 나가지만, 이 필드는 저장값이 Asia/Seoul 벽시계라(ADR 0020) 숫자를 그대로 두고 오프셋만 붙인다({@code
 * +09:00}) — 어드민 수정 화면이 이 응답을 폼에 되돌려 저장하는 왕복을 타기 때문이다(#650).
 *
 * <p>이 테스트가 잡는 회귀는 세 가지다. 애노테이션이 빠지면 존 표시가 사라지고({@code 2026-09-22 19:00:00}), common의 {@code
 * UtcLocalDateTimeSerializer}를 붙이면 존 표시는 생기지만 9시간 어긋난 순간을 가리키며({@code 2026-09-22T19:00:00Z}), 존 상수가
 * 갈리면 오프셋이 달라진다. 그래서 문자열과 순간을 따로 단언한다 — 형식만 보면 두 번째 사고를 놓친다.
 */
@ResourceLock("java.util.TimeZone.default")
class PerformanceUtcResponseContractTest {

  /** JVM 기본 존을 바꿔도 결과가 같아야 한다 — 운영은 {@code TZ=UTC}, 로컬은 KST 다. */
  private static final List<String> JVM_ZONES = List.of("UTC", "Asia/Seoul");

  static List<Arguments> bookingOpenAtCases() {
    return List.of(
        // 이슈 #671의 재현 값. 어드민이 입력한 19:00 이 응답에서도 19:00 이어야 한다.
        Arguments.of(LocalDateTime.parse("2026-09-22T19:00:00"), "2026-09-22T19:00:00+09:00"),
        // UTC 로 환산하면 전날이 되는 자리. Z 로 냈다면 날짜까지 달라져 폼 왕복이 깨진다.
        Arguments.of(LocalDateTime.parse("2027-01-01T00:00:00"), "2027-01-01T00:00:00+09:00"),
        // 초 미만 정밀도는 절삭한다. 초가 0인 값도 초를 생략하지 않는다(길이 고정).
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

  static List<Arguments> cases() {
    return JVM_ZONES.stream()
        .flatMap(
            zone ->
                bookingOpenAtCases().stream().map(c -> Arguments.of(zone, c.get()[0], c.get()[1])))
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

      // 형식 — 오프셋이 붙고, 숫자는 어드민이 입력한 벽시계 그대로다(폼 왕복이 이 동일성에 기댄다).
      assertThat(actual).isEqualTo(expected);

      // 의미 — 그 문자열이 Asia/Seoul 로 해석한 바로 그 순간을 가리킨다. 형식이 맞아도 존 상수가
      // 갈리면(예: 부모 직렬화기의 리터럴 Z) 이 단언만 깨진다.
      assertThat(OffsetDateTime.parse(actual).toInstant())
          .isEqualTo(
              ZonedDateTime.of(bookingOpenAt, PerformanceShowTimePolicy.SHOW_ZONE)
                  .toInstant()
                  .truncatedTo(ChronoUnit.SECONDS));

      // 값이 없으면 전역 NON_NULL 로 키가 빠진다 — @Schema 가 약속한 동작이다.
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
