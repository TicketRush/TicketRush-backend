package com.ticketrush.global.filter;

import com.ticketrush.global.constants.TraceIdConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  private static final String TRACE_ID_HEADER = "X-Trace-Id";
  private static final int MAX_TRACE_ID_LENGTH = 100;
  private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,100}$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    try {
      // 1. HTTP 헤더에서 traceId 추출 (없으면 생성)
      String traceId = extractOrGenerateTraceId(request);

      // 2. MDC에 traceId 저장
      MDC.put(TraceIdConstants.TRACE_ID_KEY, traceId);

      // 3. 응답 헤더에도 traceId 포함
      response.setHeader(TRACE_ID_HEADER, traceId);

      filterChain.doFilter(request, response);
    } finally {
      // 4. 요청 처리 완료 후 MDC 정리 (이 필터에서 추가한 키만 제거)
      MDC.remove(TraceIdConstants.TRACE_ID_KEY);
    }
  }

  private String extractOrGenerateTraceId(HttpServletRequest request) {
    String traceId = request.getHeader(TRACE_ID_HEADER);

    if (!isValidTraceId(traceId)) {
      return UUID.randomUUID().toString();
    }

    return traceId;
  }

  private boolean isValidTraceId(String traceId) {
    if (!StringUtils.hasText(traceId)) {
      return false;
    }

    if (traceId.length() > MAX_TRACE_ID_LENGTH) {
      return false;
    }

    if (traceId.contains("\r") || traceId.contains("\n")) {
      return false;
    }

    return TRACE_ID_PATTERN.matcher(traceId).matches();
  }
}
