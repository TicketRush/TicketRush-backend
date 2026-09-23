package com.ticketrush.boundedcontext.performance.app.support;

import com.ticketrush.boundedcontext.performance.domain.policy.PerformanceShowTimePolicy;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Asia/Seoul 벽시계로 저장된 LocalDateTime을 오프셋을 붙여 {@code yyyy-MM-dd'T'HH:mm:ss+09:00}으로 출력한다 (#671).
 *
 * <p><b>왜 이 클래스가 필요한가.</b> 전역 포맷({@code JacksonConfig}: {@code yyyy-MM-dd HH:mm:ss})은 존 표시가 없어, 서버
 * 판정이 정확해도 클라이언트가 값을 KST로 읽을지 UTC로 읽을지 정할 근거가 응답 안에 없다. 두 해석의 차이가 정확히 9시간이다.
 *
 * <p><b>왜 {@code common}의 {@code UtcLocalDateTimeSerializer}를 쓰지 않는가.</b> 그쪽은 값이 <b>이미 UTC</b>라고
 * 전제하고 {@code Z}를 리터럴로 붙이기만 한다. {@code booking_open_at}은 어드민이 오프셋 없이 입력한 KST 벽시계라(ADR 0020) 그 직렬화기를
 * 붙이면 형식은 정상으로 보이면서 9시간 어긋난 순간을 가리킨다. 상속으로 재사용하지도 않는다 — 부모의 "값은 UTC"라는 전제와 이 클래스의 전제가 반대라서 하위 타입이
 * 성립하지 않고, 두 직렬화기의 {@code handledType()}이 똑같이 {@code LocalDateTime}이라 한쪽을 다른 쪽 자리에 끼우면 이중 환산된 값이
 * 조용히 나간다.
 *
 * <p><b>왜 {@code Z}가 아니라 {@code +09:00}인가.</b> 같은 API 군의 다른 시각 필드는 #646에서 전부 {@code ...Z}로 통일됐지만, 이
 * 필드는 <b>읽기와 쓰기가 같은 화면을 왕복</b>한다 — 어드민 수정 화면이 별도 관리자 상세 API 없이 이 응답을 재사용하고(#650), 요청 DTO({@code
 * PerformanceCreateRequest}·{@code PerformancePatchRequest})는 오프셋 없는 KST를 받는다. {@code Z}로 내면 응답 숫자가
 * 어드민 입력과 9시간 달라져, 폼이 그 값을 그대로 되돌려 저장하는 순간 오픈 시각이 조용히 앞당겨진다. {@code +09:00}은 숫자를 입력값과 같게 유지하면서 존
 * 표시만 더하므로 그 왕복이 자동으로 맞고, 운영 대조에도 환산이 필요 없다.
 *
 * <p>존 해석은 {@link PerformanceShowTimePolicy#SHOW_ZONE}을 그대로 참조한다. 상수를 복제하면 오픈 판정과 응답 직렬화가 서로 다른 존을
 * 쓰게 되고, 그것이 이 이슈가 고치는 증상 자체다.
 *
 * <p>초 단위로 절삭한다 — 저장 컬럼이 초 정밀도이므로 나노초는 응답에 실을 값이 아니다. ISO 표준 포맷터를 쓰지 않는 이유는 {@code
 * ISO_OFFSET_DATE_TIME}이 초가 0일 때 초를 생략해({@code T19:00+09:00}) 길이가 들쭉날쭉해지기 때문이다.
 *
 * <p>Asia/Seoul은 1988년 이후 일광절약시간이 없어 {@code atZone}에 중의성이 없다. 도입되면 이 클래스와 정책의 해석이 함께 재검토돼야 한다.
 */
public class SeoulWallClockSerializer extends StdSerializer<LocalDateTime> {

  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

  public SeoulWallClockSerializer() {
    super(LocalDateTime.class);
  }

  @Override
  public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext ctxt) {
    gen.writeString(
        value
            .atZone(PerformanceShowTimePolicy.SHOW_ZONE)
            .truncatedTo(ChronoUnit.SECONDS)
            .format(FORMATTER));
  }
}
