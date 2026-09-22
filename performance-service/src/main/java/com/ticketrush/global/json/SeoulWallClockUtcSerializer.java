package com.ticketrush.global.json;

import com.ticketrush.boundedcontext.performance.domain.policy.PerformanceShowTimePolicy;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;

/**
 * Asia/Seoul 벽시계로 저장된 LocalDateTime을 UTC로 옮겨 {@code yyyy-MM-dd'T'HH:mm:ss'Z'}로 출력한다 (#671).
 *
 * <p><b>부모를 그대로 쓸 수 없어서 존재한다.</b> {@link UtcLocalDateTimeSerializer}는 값이 <b>이미 UTC</b>라고 전제하고
 * {@code Z}를 리터럴로 붙이기만 한다. {@code booking_open_at}은 어드민이 오프셋 없이 입력한 KST 벽시계라(ADR 0020) 그 직렬화기를 그대로
 * 붙이면 9시간 어긋난 시각이 나간다 — 숫자는 그대로인데 뜻이 바뀌는 형태라 응답만 보고는 틀린 줄 알 수 없다.
 *
 * <p><b>존 해석은 {@link PerformanceShowTimePolicy#SHOW_ZONE}을 그대로 참조한다.</b> 상수를 복제하면 오픈 판정과 응답 직렬화가 서로
 * 다른 존을 쓰게 되고, 그것이 정확히 이 이슈가 고치는 증상이다.
 *
 * <p>변환만 앞에 붙이고 포맷·초 절삭은 부모에 맡긴다. 형식이 {@code docs/utc-timestamp-rollout.md}의 단일 규칙에서 갈라지지 않게 하려면
 * 포맷터가 한 곳에만 있어야 한다.
 *
 * <p>Asia/Seoul은 1988년 이후 일광절약시간이 없어 {@code atZone}에 중의성이 없다. 도입되면 이 클래스와 정책의 해석이 함께 재검토돼야 한다.
 */
public class SeoulWallClockUtcSerializer extends UtcLocalDateTimeSerializer {

  @Override
  public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext ctxt) {
    super.serialize(
        value
            .atZone(PerformanceShowTimePolicy.SHOW_ZONE)
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime(),
        gen,
        ctxt);
  }
}
