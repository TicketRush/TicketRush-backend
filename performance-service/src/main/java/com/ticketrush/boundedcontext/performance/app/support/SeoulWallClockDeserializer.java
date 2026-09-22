package com.ticketrush.boundedcontext.performance.app.support;

import com.ticketrush.boundedcontext.performance.domain.policy.PerformanceShowTimePolicy;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * {@link SeoulWallClockSerializer}가 쓴 오프셋 표기를 다시 Asia/Seoul 벽시계로 읽는다 (#671).
 *
 * <p><b>캐시 왕복 때문에 있다.</b> 이 응답 필드들은 밖으로 나가기만 하는 것처럼 보이지만, 공연 목록은 {@code PerformanceListSlice}로
 * Redis 에 캐시된다({@code CacheConfig}, TTL 30초). 캐시에 쓸 때는 필드의 {@code @JsonSerialize}가 적용돼 {@code
 * 2027-01-10T19:00:00+09:00}이 저장되는데, 읽을 때 {@code LocalDateTime}의 기본 역직렬화기는 오프셋이 붙은 문자열을 파싱하지 못한다.
 *
 * <p><b>깨져도 조용하다.</b> {@code CacheConfig}의 fail-open 에러 핸들러가 캐시 조회 예외를 삼키고 미스로 취급하므로, 짝이 없으면 예외가
 * 아니라 <b>영구 캐시 미스</b>로 나타난다 — 기능은 멀쩡해 보이고 DB 부하만 는다. 그래서 오프셋으로 나가는 필드는 읽는 짝을 함께 둔다.
 *
 * <p>오프셋을 그대로 신뢰하지 않고 {@link PerformanceShowTimePolicy#SHOW_ZONE}으로 다시 맞춘 뒤 벽시계를 뽑는다. 저장된 값이 어떤
 * 오프셋으로 적혀 있든 DTO 가 담는 의미(서울 벽시계)는 하나여야 하기 때문이다.
 */
public class SeoulWallClockDeserializer extends StdDeserializer<LocalDateTime> {

  public SeoulWallClockDeserializer() {
    super(LocalDateTime.class);
  }

  @Override
  public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) {
    return OffsetDateTime.parse(p.getString())
        .atZoneSameInstant(PerformanceShowTimePolicy.SHOW_ZONE)
        .toLocalDateTime();
  }
}
