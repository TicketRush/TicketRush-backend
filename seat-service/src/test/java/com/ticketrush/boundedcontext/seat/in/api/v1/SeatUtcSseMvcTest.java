package com.ticketrush.boundedcontext.seat.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusChangedResponse;
import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusSseEmitterRegistry;
import com.ticketrush.boundedcontext.seat.in.sse.SeatStatusSseSubscriber;
import com.ticketrush.boundedcontext.seat.out.sse.SeatStatusSseEventSender;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.global.types.SeatStatus;
import com.ticketrush.support.WebMvcSliceTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcSliceTest(SeatController.class)
@Import({SecurityConfig.class, CustomSecurityProperties.class, GatewayHeaderFilter.class})
@TestPropertySource(properties = "gateway.internal-token=test-token")
@ResourceLock("java.util.TimeZone.default")
class SeatUtcSseMvcTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private SeatFacade seatFacade;

  @ParameterizedTest
  @ValueSource(strings = {"UTC", "Asia/Seoul"})
  void actualSseFrames_useUtcAndOmitNullForReleaseAndSold(String zone) throws Exception {
    TimeZone previous = TimeZone.getDefault();
    SeatStatusSseEmitterRegistry registry = new SeatStatusSseEmitterRegistry();
    SeatStatusSseSubscriber subscriber = new SeatStatusSseSubscriber(registry);
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.initialize();
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    SeatStatusSseEventSender sender = new SeatStatusSseEventSender(registry, executor, metrics);
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      assertThat(TimeZone.getDefault().getID()).isEqualTo(zone);
      given(seatFacade.subscribeSeatStatus(3L)).willAnswer(ignored -> subscriber.subscribe(3L));
      final MvcResult result =
          mockMvc
              .perform(get("/api/v1/seat/3/seat-status/stream"))
              .andExpect(status().isOk())
              .andExpect(request().asyncStarted())
              .andReturn();

      sender.send(
          new SeatStatusChangedResponse(
              3L,
              1L,
              2L,
              "A-1",
              SeatStatus.HOLD,
              LocalDateTime.of(2026, 1, 1, 0, 0, 0, 987654321)));
      sender.send(new SeatStatusChangedResponse(3L, 1L, 2L, "A-1", SeatStatus.AVAILABLE, null));
      sender.send(new SeatStatusChangedResponse(3L, 1L, 2L, "A-1", SeatStatus.SOLD, null));
      // 단일 작업자 큐의 뒤에 배리어를 넣어 실제 전송 완료를 제한 시간 안에 기다린다.
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);

      assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
      String frames = result.getResponse().getContentAsString();
      assertThat(frames).contains("event:connected");
      String[] changes = frames.split("event:seat-status-changed");
      assertThat(changes).hasSize(4);
      assertThat(changes[1])
          .contains("data:")
          .contains("\"hold_expired_at\":\"2026-01-01T00:00:00Z\"")
          .contains("\"seat_status\":\"HOLD\"");
      assertThat(changes[2])
          .contains("\"seat_status\":\"AVAILABLE\"")
          .doesNotContain("hold_expired_at");
      assertThat(changes[3]).contains("\"seat_status\":\"SOLD\"").doesNotContain("hold_expired_at");
    } finally {
      if (registry.get(3L) != null) {
        for (SseEmitter emitter : registry.get(3L)) {
          emitter.complete();
          registry.remove(3L, emitter);
        }
      }
      executor.shutdown();
      metrics.close();
      TimeZone.setDefault(previous);
    }
  }
}
