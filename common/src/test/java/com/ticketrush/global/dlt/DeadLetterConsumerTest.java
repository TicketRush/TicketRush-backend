package com.ticketrush.global.dlt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.notification.Notifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.serializer.SerializationUtils;

@ExtendWith(MockitoExtension.class)
class DeadLetterConsumerTest {

  @InjectMocks private DeadLetterConsumer deadLetterConsumer;

  @Mock private DeadLetterRecordRepository deadLetterRecordRepository;
  @Mock private Notifier notifier;
  @Mock private Acknowledgment ack;

  private static byte[] intBytes(int value) {
    return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
  }

  private static byte[] longBytes(long value) {
    return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
  }

  private static byte[] str(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("envelope 값이면 DLT 헤더와 envelope에서 레코드를 매핑해 저장하고, 알림/ack를 호출한다")
  void consume_maps_envelope_and_saves() {
    // given
    DomainEventEnvelope envelope =
        new DomainEventEnvelope(
            "evt-1",
            "BookingCreatedEvent",
            Instant.parse("2026-05-27T06:00:00Z"),
            "booking-created-topic",
            "{\"booking_id\":100}",
            "trace-1");
    ConsumerRecord<String, Object> record =
        new ConsumerRecord<>("booking-created-topic.DLT", 0, 42L, "100", envelope);

    given(deadLetterRecordRepository.save(any(DeadLetterRecord.class)))
        .willAnswer(invocation -> invocation.getArgument(0));

    // when
    deadLetterConsumer.consume(
        record,
        str("booking-created-topic"),
        intBytes(3),
        longBytes(99L),
        str("java.lang.IllegalStateException"),
        str("boom"),
        ack);

    // then
    ArgumentCaptor<DeadLetterRecord> captor = ArgumentCaptor.forClass(DeadLetterRecord.class);
    verify(deadLetterRecordRepository).save(captor.capture());
    DeadLetterRecord saved = captor.getValue();

    assertThat(saved.getOriginalTopic()).isEqualTo("booking-created-topic");
    assertThat(saved.getOriginalPartition()).isEqualTo(3);
    assertThat(saved.getOriginalOffset()).isEqualTo(99L);
    assertThat(saved.getMessageKey()).isEqualTo("100");
    assertThat(saved.getEventType()).isEqualTo("BookingCreatedEvent");
    assertThat(saved.getEventId()).isEqualTo("evt-1");
    assertThat(saved.getPayload()).isEqualTo("{\"booking_id\":100}");
    assertThat(saved.getExceptionFqcn()).isEqualTo("java.lang.IllegalStateException");
    assertThat(saved.getExceptionMessage()).isEqualTo("boom");
    // occurredAt 제거됨(#9) — 저장 시각은 created_at(JPA Auditing)으로 관리

    // 알림에는 자유서식 예외 메시지 대신 exceptionFqcn(예외 타입)을 전달한다(#6).
    verify(notifier).send(any(), eq("java.lang.IllegalStateException"), any());
    verify(ack).acknowledge();
  }

  @Test
  @DisplayName("payload에 PII(이메일)가 있으면 마스킹된 값으로 저장한다")
  void consume_masks_pii_in_payload_before_saving() {
    // given: payload에 이메일이 포함된 envelope
    DomainEventEnvelope envelope =
        new DomainEventEnvelope(
            "evt-1",
            "BookingCreatedEvent",
            Instant.parse("2026-05-27T06:00:00Z"),
            "booking-created-topic",
            "{\"email\":\"user@example.com\"}",
            "trace-1");
    ConsumerRecord<String, Object> record =
        new ConsumerRecord<>("booking-created-topic.DLT", 0, 42L, "100", envelope);

    given(deadLetterRecordRepository.save(any(DeadLetterRecord.class)))
        .willAnswer(invocation -> invocation.getArgument(0));

    // when
    deadLetterConsumer.consume(
        record,
        str("booking-created-topic"),
        intBytes(3),
        longBytes(99L),
        str("java.lang.IllegalStateException"),
        str("user@example.com 처리 실패"),
        ack);

    // then
    ArgumentCaptor<DeadLetterRecord> captor = ArgumentCaptor.forClass(DeadLetterRecord.class);
    verify(deadLetterRecordRepository).save(captor.capture());
    DeadLetterRecord saved = captor.getValue();

    assertThat(saved.getPayload()).doesNotContain("user@example.com");
    assertThat(saved.getPayload()).contains("u***@***");
    assertThat(saved.getExceptionMessage()).doesNotContain("user@example.com");
    assertThat(saved.getExceptionMessage()).contains("u***@***");
  }

  @Test
  @DisplayName("역직렬화 실패(value=null)일 때 springDeserializerExceptionValue 헤더에서 원본 payload를 추출한다")
  void consume_extracts_raw_payload_from_deserialization_exception_header() {
    // given: ErrorHandlingDeserializer가 실제로 생성하는 헤더와 동일한 형식으로 구성한다.
    // SerializationUtils.deserializationException()은 DeserializationExceptionHeader(타입-안전)를 추가하며,
    // 이것이 프로덕션 getExceptionFromHeader() 타입 검사를 통과하는 유일한 헤더 형태다(자기-인코딩→자기-디코딩 왕복 금지, #B).
    byte[] rawBytes = "raw-broken-payload".getBytes(StandardCharsets.UTF_8);
    ConsumerRecord<String, Object> record =
        new ConsumerRecord<>("some-topic.DLT", 0, 7L, null, null);
    SerializationUtils.deserializationException(
        record.headers(), rawBytes, new RuntimeException("codec error"), false);

    given(deadLetterRecordRepository.save(any(DeadLetterRecord.class)))
        .willAnswer(invocation -> invocation.getArgument(0));

    // when
    deadLetterConsumer.consume(record, null, null, null, null, null, ack);

    // then
    ArgumentCaptor<DeadLetterRecord> captor = ArgumentCaptor.forClass(DeadLetterRecord.class);
    verify(deadLetterRecordRepository).save(captor.capture());
    DeadLetterRecord saved = captor.getValue();

    assertThat(saved.getOriginalTopic()).isEqualTo("some-topic");
    assertThat(saved.getOriginalPartition()).isZero();
    assertThat(saved.getOriginalOffset()).isEqualTo(7L);
    assertThat(saved.getEventType()).isNull();
    assertThat(saved.getEventId()).isNull();
    // getExceptionFromHeader()가 DeserializationExceptionHeader를 타입-안전하게 역직렬화해 원본 payload를 복원한다.
    assertThat(saved.getPayload()).isEqualTo("raw-broken-payload");

    verify(notifier).send(any(), any(), any());
    verify(ack).acknowledge();
  }

  @Test
  @DisplayName("알림 전송이 실패해도 예외를 삼키고 저장 후 ack한다")
  void consume_acks_even_when_notify_fails() {
    // given
    ConsumerRecord<String, Object> record =
        new ConsumerRecord<>("some-topic.DLT", 0, 1L, null, "payload");
    given(deadLetterRecordRepository.save(any(DeadLetterRecord.class)))
        .willAnswer(invocation -> invocation.getArgument(0));
    org.mockito.BDDMockito.willThrow(new RuntimeException("slack down"))
        .given(notifier)
        .send(any(), any(), any());

    // when
    deadLetterConsumer.consume(record, null, null, null, null, null, ack);

    // then
    verify(deadLetterRecordRepository).save(any(DeadLetterRecord.class));
    verify(ack).acknowledge();
  }

  @Test
  @DisplayName("DB 저장이 실패하면 ack하지 않고 예외를 전파해 재시도되게 한다")
  void consume_does_not_ack_when_save_fails() {
    // given
    ConsumerRecord<String, Object> record =
        new ConsumerRecord<>("some-topic.DLT", 0, 1L, null, "payload");
    given(deadLetterRecordRepository.save(any(DeadLetterRecord.class)))
        .willThrow(new RuntimeException("db down"));

    // when & then
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> deadLetterConsumer.consume(record, null, null, null, null, null, ack))
        .isInstanceOf(RuntimeException.class);

    org.mockito.Mockito.verify(ack, org.mockito.Mockito.never()).acknowledge();
    org.mockito.Mockito.verify(notifier, org.mockito.Mockito.never())
        .send(any(), any(), any(Map.class));
  }
}
