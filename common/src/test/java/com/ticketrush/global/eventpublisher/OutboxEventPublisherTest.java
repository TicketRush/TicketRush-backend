package com.ticketrush.global.eventpublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.outbox.OutboxEntity;
import com.ticketrush.global.outbox.OutboxRepository;
import com.ticketrush.global.outbox.OutboxStatus;
import com.ticketrush.shared.booking.event.BookingCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

  @InjectMocks private OutboxEventPublisher outboxEventPublisher;

  @Mock private OutboxRepository outboxRepository;
  @Mock private JsonConverter jsonConverter;

  @Test
  @DisplayName("성공: 도메인 이벤트를 PENDING 상태의 Outbox row로 저장한다")
  void publish_saves_pending_outbox_row() {
    // given
    BookingCreatedEvent event = new BookingCreatedEvent(100L, "BOOK-1234", 3L, 2L, 1L);
    String payload = "{\"booking_id\":100}";
    given(jsonConverter.serialize(event)).willReturn(payload);

    // when
    outboxEventPublisher.publish(event);

    // then
    ArgumentCaptor<OutboxEntity> captor = ArgumentCaptor.forClass(OutboxEntity.class);
    verify(outboxRepository).save(captor.capture());

    OutboxEntity saved = captor.getValue();
    // aggregateType은 이벤트 패키지(com.ticketrush.shared.booking.event)에서 유도된다.
    assertThat(saved.getAggregateType()).isEqualTo("Booking");
    // aggregateId와 messageKey는 모두 event.key()(= bookingId)에서 온다.
    assertThat(saved.getAggregateId()).isEqualTo("100");
    assertThat(saved.getMessageKey()).isEqualTo("100");
    assertThat(saved.getEventType()).isEqualTo("BookingCreatedEvent");
    assertThat(saved.getTopic()).isEqualTo("booking-created-topic");
    assertThat(saved.getPayload()).isEqualTo(payload);
    assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(saved.getRetryCount()).isZero();
    assertThat(saved.getEventId()).isNotBlank();
  }
}
