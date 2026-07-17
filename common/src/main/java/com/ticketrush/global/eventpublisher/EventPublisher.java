package com.ticketrush.global.eventpublisher;

import com.ticketrush.global.event.DomainEvent;

public interface EventPublisher {
  void publish(DomainEvent event);
}
