package com.ticketrush.global.eventpublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.outbox.OutboxEntity;
import com.ticketrush.global.outbox.OutboxRepository;
import com.ticketrush.global.outbox.OutboxStatus;
import com.ticketrush.shared.booking.event.BookingCreatedEvent;
import com.ticketrush.shared.payment.event.PaymentConfirmedEvent;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

  @InjectMocks private OutboxEventPublisher outboxEventPublisher;

  @Mock private OutboxRepository outboxRepository;
  @Mock private JsonConverter jsonConverter;

  @AfterEach
  void tearDown() {
    // 정적 ThreadLocal 상태가 다른 테스트로 새지 않도록 정리한다.
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  @DisplayName("성공: 활성 트랜잭션 내에서 도메인 이벤트를 PENDING 상태의 Outbox row로 저장한다")
  void publish_saves_pending_outbox_row() {
    // given
    TransactionSynchronizationManager.setActualTransactionActive(true);
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

  @Test
  @DisplayName("성공: aggregateId는 애그리거트 PK, messageKey는 파티션 키로 분리 저장한다")
  void publish_separates_aggregate_id_from_message_key() {
    // given: Payment 이벤트는 aggregateId=paymentId, key=bookingId로 서로 다르다.
    TransactionSynchronizationManager.setActualTransactionActive(true);
    PaymentConfirmedEvent event =
        new PaymentConfirmedEvent(500L, 100L, 3L, 1L, 10000L, LocalDateTime.of(2026, 5, 27, 15, 0));
    given(jsonConverter.serialize(event)).willReturn("{\"payment_id\":500}");

    // when
    outboxEventPublisher.publish(event);

    // then
    ArgumentCaptor<OutboxEntity> captor = ArgumentCaptor.forClass(OutboxEntity.class);
    verify(outboxRepository).save(captor.capture());

    OutboxEntity saved = captor.getValue();
    assertThat(saved.getAggregateType()).isEqualTo("Payment");
    assertThat(saved.getAggregateId()).isEqualTo("500"); // paymentId
    assertThat(saved.getMessageKey()).isEqualTo("100"); // bookingId(파티션 키)
  }

  @Test
  @DisplayName("실패: 활성 트랜잭션이 없으면 원자성 보장을 위해 예외를 던지고 저장하지 않는다")
  void publish_throws_when_no_active_transaction() {
    // given: 활성 트랜잭션 없음 (기본 상태)
    BookingCreatedEvent event = new BookingCreatedEvent(100L, "BOOK-1234", 3L, 2L, 1L);

    // when & then
    assertThatThrownBy(() -> outboxEventPublisher.publish(event))
        .isInstanceOf(IllegalStateException.class);
    verify(outboxRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }
}
