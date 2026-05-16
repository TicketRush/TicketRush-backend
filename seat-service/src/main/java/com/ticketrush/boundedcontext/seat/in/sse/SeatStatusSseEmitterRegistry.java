package com.ticketrush.boundedcontext.seat.in.sse;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusChangedResponse;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventSender;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
public class SeatStatusSseEmitterRegistry implements SeatStatusEventSender {

  private static final long SSE_TIMEOUT_MILLIS = 30 * 60 * 1000L;
  private static final String EVENT_NAME = "seat-status-changed";

  private final Map<Long, List<SseEmitter>> emittersByPerformanceId = new ConcurrentHashMap<>();

  public SseEmitter subscribe(Long performanceId) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
    emittersByPerformanceId
        .computeIfAbsent(performanceId, ignored -> new CopyOnWriteArrayList<>())
        .add(emitter);

    emitter.onCompletion(() -> removeEmitter(performanceId, emitter));
    emitter.onTimeout(
        () -> {
          removeEmitter(performanceId, emitter);
          emitter.complete();
        });
    emitter.onError(ignored -> removeEmitter(performanceId, emitter));

    sendConnectEvent(performanceId, emitter);
    return emitter;
  }

  @Override
  public void send(SeatStatusChangedResponse event) {
    List<SseEmitter> emitters = emittersByPerformanceId.get(event.performanceId());
    if (emitters == null || emitters.isEmpty()) {
      return;
    }

    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name(EVENT_NAME).data(event));
      } catch (IOException | IllegalStateException e) {
        removeEmitter(event.performanceId(), emitter);
        emitter.completeWithError(e);
      }
    }
  }

  private void sendConnectEvent(Long performanceId, SseEmitter emitter) {
    try {
      emitter.send(SseEmitter.event().name("connected").data("connected"));
    } catch (IOException | IllegalStateException e) {
      removeEmitter(performanceId, emitter);
      emitter.completeWithError(e);
    }
  }

  private void removeEmitter(Long performanceId, SseEmitter emitter) {
    List<SseEmitter> emitters = emittersByPerformanceId.get(performanceId);
    if (emitters == null) {
      return;
    }

    emitters.remove(emitter);
    if (emitters.isEmpty()) {
      emittersByPerformanceId.remove(performanceId);
    }
  }
}
