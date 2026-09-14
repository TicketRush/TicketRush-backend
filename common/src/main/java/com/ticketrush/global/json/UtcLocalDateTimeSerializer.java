package com.ticketrush.global.json;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * UTC 기준으로 저장된 LocalDateTime을 응답에서 {@code yyyy-MM-dd'T'HH:mm:ss'Z'}로 출력한다.
 *
 * <p>전역 포맷(JacksonConfig)은 요청·이벤트·outbox 계약이 공유하므로 바꾸지 않고, 외부 응답의 발생·만료 시각 필드에만
 * {@code @JsonSerialize(using = UtcLocalDateTimeSerializer.class)}로 지정한다.
 */
public class UtcLocalDateTimeSerializer extends StdSerializer<LocalDateTime> {

  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

  public UtcLocalDateTimeSerializer() {
    super(LocalDateTime.class);
  }

  @Override
  public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext ctxt) {
    gen.writeString(value.truncatedTo(ChronoUnit.SECONDS).format(FORMATTER));
  }
}
