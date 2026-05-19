package com.ticketrush.boundedcontext.seat.app.support;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface SeatStatusStreamSubscriber {

  SseEmitter subscribe(Long performanceId);
}
