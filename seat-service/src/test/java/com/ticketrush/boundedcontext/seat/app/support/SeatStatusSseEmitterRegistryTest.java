package com.ticketrush.boundedcontext.seat.app.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SeatStatusSseEmitterRegistryTest {

  private final SeatStatusSseEmitterRegistry seatStatusSseEmitterRegistry =
      new SeatStatusSseEmitterRegistry();

  @Test
  @DisplayName("동일 공연의 일부 emitter만 제거되면 나머지 구독은 유지된다")
  void removeKeepsOtherEmittersForSamePerformance() {
    // given
    Long performanceId = 1L;
    SseEmitter firstEmitter = new SseEmitter();
    SseEmitter secondEmitter = new SseEmitter();
    seatStatusSseEmitterRegistry.add(performanceId, firstEmitter);
    seatStatusSseEmitterRegistry.add(performanceId, secondEmitter);

    // when
    seatStatusSseEmitterRegistry.remove(performanceId, firstEmitter);

    // then
    assertThat(seatStatusSseEmitterRegistry.get(performanceId)).containsExactly(secondEmitter);
  }

  @Test
  @DisplayName("마지막 emitter가 제거되면 공연별 registry 항목을 제거한다")
  void removeDeletesPerformanceEntryWhenLastEmitterRemoved() {
    // given
    Long performanceId = 1L;
    SseEmitter emitter = new SseEmitter();
    seatStatusSseEmitterRegistry.add(performanceId, emitter);

    // when
    seatStatusSseEmitterRegistry.remove(performanceId, emitter);

    // then
    assertThat(seatStatusSseEmitterRegistry.get(performanceId)).isNull();
  }
}
