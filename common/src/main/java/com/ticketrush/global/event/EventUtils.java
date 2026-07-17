package com.ticketrush.global.event;

import com.ticketrush.global.constants.TraceIdConstants;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

// 이벤트 생성 시 traceId를 추출하는 유틸리티 클래스
public final class EventUtils {

  private static final String UNKNOWN_TRACE_ID = "Unknown";

  private EventUtils() {}

  /**
   * MDC에서 traceId를 추출합니다.
   *
   * @return MDC에 저장된 traceId, 없으면 "Unknown"
   */
  public static String extractTraceId() {
    String traceId = MDC.get(TraceIdConstants.TRACE_ID_KEY);
    return StringUtils.hasText(traceId) ? traceId : UNKNOWN_TRACE_ID;
  }
}
