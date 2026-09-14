package com.ticketrush.boundedcontext.user.app.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

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
class UserUtcResponseContractTest {

  static Stream<Arguments> responses() {
    return Stream.of(
        Arguments.of(
            "UserMeResponse",
            (Function<LocalDateTime, Object>)
                time -> new UserMeResponse("회원", "a@b.com", time, "USER"),
            List.of("created_at")));
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
