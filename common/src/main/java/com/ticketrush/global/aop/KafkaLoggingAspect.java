package com.ticketrush.global.aop;

import com.ticketrush.global.constants.TraceIdConstants;
import com.ticketrush.global.event.DomainEventEnvelope;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Aspect
@Component
public class KafkaLoggingAspect {

  private static final String UNKNOWN_TRACE_ID = "Unknown";

  @Pointcut("@annotation(org.springframework.kafka.annotation.KafkaListener)")
  public void kafkaListenerPointcut() {}

  @Around("kafkaListenerPointcut()")
  public Object logKafkaEvent(ProceedingJoinPoint joinPoint) throws Throwable {
    String targetClass = joinPoint.getTarget().getClass().getSimpleName();
    String methodName = joinPoint.getSignature().getName();
    Object[] args = joinPoint.getArgs();
    long startNanos = System.nanoTime();

    String eventTraceId = extractTraceId(args);
    String originalTraceId = MDC.get(TraceIdConstants.TRACE_ID_KEY);
    boolean mdcModified = false;

    if (StringUtils.hasText(eventTraceId) && !UNKNOWN_TRACE_ID.equals(eventTraceId)) {
      MDC.put(TraceIdConstants.TRACE_ID_KEY, eventTraceId);
      mdcModified = true;
    }

    try {
      Object result = joinPoint.proceed();
      long elapsedMillis = getElapsedMillis(startNanos);

      log.info(
          "[KAFKA CONSUME SUCCESS] {}.{} | TraceId: {} | Time: {}ms",
          targetClass,
          methodName,
          eventTraceId,
          elapsedMillis);

      return result;
    } catch (Exception e) {
      long elapsedMillis = getElapsedMillis(startNanos);

      log.error(
          "[KAFKA CONSUME FAILED] {}.{} | TraceId: {} | Time: {}ms",
          targetClass,
          methodName,
          eventTraceId,
          elapsedMillis,
          e);

      throw e;
    } finally {
      if (mdcModified) {
        restoreTraceId(originalTraceId);
      }
    }
  }

  private long getElapsedMillis(long startNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
  }

  private void restoreTraceId(String originalTraceId) {
    if (originalTraceId != null) {
      MDC.put(TraceIdConstants.TRACE_ID_KEY, originalTraceId);
    } else {
      MDC.remove(TraceIdConstants.TRACE_ID_KEY);
    }
  }

  private String extractTraceId(Object[] args) {
    if (args == null) {
      return UNKNOWN_TRACE_ID;
    }

    for (Object arg : args) {
      if (arg instanceof DomainEventEnvelope envelope && StringUtils.hasText(envelope.traceId())) {
        return envelope.traceId();
      }
    }

    return UNKNOWN_TRACE_ID;
  }
}
