package com.ticketrush.boundedcontext.seat.out.sse;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusChangedResponse;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventSender;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusSseEmitterRegistry;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
public class SeatStatusSseEventSender implements SeatStatusEventSender {

  private static final String EVENT_NAME = "seat-status-changed";

  private final SeatStatusSseEmitterRegistry seatStatusSseEmitterRegistry;
  private final Executor seatStatusSseExecutor;

  public SeatStatusSseEventSender(
      SeatStatusSseEmitterRegistry seatStatusSseEmitterRegistry,
      @Qualifier("seatStatusSseExecutor") Executor seatStatusSseExecutor) {
    this.seatStatusSseEmitterRegistry = seatStatusSseEmitterRegistry;
    this.seatStatusSseExecutor = seatStatusSseExecutor;
  }

  @Override
  public void send(SeatStatusChangedResponse event) {
    try {
      seatStatusSseExecutor.execute(() -> broadcast(event));
    } catch (RejectedExecutionException e) {
      log.warn("좌석 상태 SSE 이벤트 전송 작업이 거부되었습니다. event: {}", event, e);
    }
  }

  private void broadcast(SeatStatusChangedResponse event) {
    List<SseEmitter> emitters = seatStatusSseEmitterRegistry.get(event.performanceId());
    if (emitters == null || emitters.isEmpty()) {
      return;
    }

    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name(EVENT_NAME).data(event));
      } catch (IOException | IllegalStateException e) {
        seatStatusSseEmitterRegistry.remove(event.performanceId(), emitter);
        emitter.completeWithError(e);
      }
    }
  }
}
