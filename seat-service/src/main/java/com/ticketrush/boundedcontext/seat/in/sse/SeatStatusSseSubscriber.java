package com.ticketrush.boundedcontext.seat.in.sse;

import com.ticketrush.boundedcontext.seat.app.support.SeatStatusSseEmitterRegistry;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusStreamSubscriber;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
@RequiredArgsConstructor
public class SeatStatusSseSubscriber implements SeatStatusStreamSubscriber {

  private static final long SSE_TIMEOUT_MILLIS = 30 * 60 * 1000L;

  private final SeatStatusSseEmitterRegistry seatStatusSseEmitterRegistry;

  @Override
  public SseEmitter subscribe(Long performanceId) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
    seatStatusSseEmitterRegistry.add(performanceId, emitter);

    emitter.onCompletion(() -> seatStatusSseEmitterRegistry.remove(performanceId, emitter));
    emitter.onTimeout(
        () -> {
          seatStatusSseEmitterRegistry.remove(performanceId, emitter);
          emitter.complete();
        });
    emitter.onError(ignored -> seatStatusSseEmitterRegistry.remove(performanceId, emitter));

    sendConnectEvent(performanceId, emitter);
    return emitter;
  }

  private void sendConnectEvent(Long performanceId, SseEmitter emitter) {
    try {
      emitter.send(SseEmitter.event().name("connected").data("connected"));
    } catch (IOException | IllegalStateException e) {
      seatStatusSseEmitterRegistry.remove(performanceId, emitter);
      emitter.completeWithError(e);
    }
  }
}
