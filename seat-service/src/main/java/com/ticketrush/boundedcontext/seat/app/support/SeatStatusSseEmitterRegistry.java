package com.ticketrush.boundedcontext.seat.app.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class SeatStatusSseEmitterRegistry {

  private final Map<Long, List<SseEmitter>> emittersByPerformanceId = new ConcurrentHashMap<>();

  public void add(Long performanceId, SseEmitter emitter) {
    emittersByPerformanceId
        .computeIfAbsent(performanceId, ignored -> new CopyOnWriteArrayList<>())
        .add(emitter);
  }

  public List<SseEmitter> get(Long performanceId) {
    return emittersByPerformanceId.get(performanceId);
  }

  public void remove(Long performanceId, SseEmitter emitter) {
    emittersByPerformanceId.computeIfPresent(
        performanceId,
        (ignored, emitters) -> {
          emitters.remove(emitter);
          return emitters.isEmpty() ? null : emitters;
        });
  }
}
