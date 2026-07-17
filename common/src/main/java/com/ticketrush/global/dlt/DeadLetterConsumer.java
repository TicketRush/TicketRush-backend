package com.ticketrush.global.dlt;

import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.notification.Notifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.log.LogAccessor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 최대 재시도 초과로 {@code .DLT}에 적재된 실패 메시지를 소비해 영구 저장하고 운영자에게 알린다.
 *
 * <p>{@code app.dlt.monitor.enabled=true}인 <b>단일 서비스</b>(기본: booking-service)에서만 활성화된다.
 *
 * <p><b>topicPattern</b>: {@code .*(?<!\.DLT)\.DLT} — 접미사가 정확히 하나인 {@code .DLT} 토픽만 매칭하며 {@code
 * .DLT.DLT}(DB 저장 실패 재시도 소진 후 생성될 수 있는 중첩 DLT)는 제외한다. 부정형 lookbehind {@code (?<!\.DLT)}로 바로 앞에
 * {@code .DLT}가 없는 경우만 매칭한다.
 *
 * <p>처리 순서: <b>에러 로그 → {@link DeadLetterRecord} 저장 → {@link Notifier#send} → 수동 ack</b>. 컨테이너는
 * {@code AckMode.MANUAL_IMMEDIATE}(KafkaConfig)이므로 반드시 수동 ack 한다.
 *
 * <p><b>실패 정책(#50 결정4)</b>:
 *
 * <ul>
 *   <li>알림 실패는 본 흐름을 깨지 않도록 삼킨다.
 *   <li>DB 저장 성공 후에만 ack 한다.
 *   <li>DB 저장 실패 시엔 ack하지 않고 예외를 던져 {@code KafkaConfig}의 {@code DefaultErrorHandler}가 재시도(지수 백오프
 *       5회)하게 한다. 재시도가 모두 소진되면 {@code .DLT.DLT}로 재발행될 수 있으나, 위 topicPattern이 이를 매칭하지 않아 무한 증식을
 *       차단한다. 이는 5회 상한 뒤에만 발생하는 지속적 DB 장애(별도 P1)에 한정된다.
 * </ul>
 *
 * <p><b>보안(#6)</b>: 알림 본문에는 예외 타입(exceptionFqcn)과 안전한 식별자만 포함한다. 자유서식 예외 메시지(exceptionMessage)는
 * DB에만 저장하고 외부 알림에는 보내지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.dlt.monitor", name = "enabled", havingValue = "true")
public class DeadLetterConsumer {

  private static final String DLT_SUFFIX = ".DLT";

  /**
   * spring-kafka {@link SerializationUtils#getExceptionFromHeader} 호출에 필요한 LogAccessor.
   *
   * <p>SLF4J {@code @Slf4j}와 별개로, spring-kafka 헬퍼 API 시그니처가 {@link LogAccessor}를 요구해 유지한다.
   */
  private static final LogAccessor LOG_ACCESSOR = new LogAccessor(DeadLetterConsumer.class);

  private final DeadLetterRecordRepository deadLetterRecordRepository;
  private final Notifier notifier;
  private final MeterRegistry meterRegistry;

  /**
   * topicPattern: 접미사가 정확히 하나인 {@code .DLT} 토픽만 매칭. {@code .DLT.DLT}는 부정형 lookbehind로 제외한다.
   *
   * <p>검증: {@code "X.DLT".matches(".*(?<!\\.DLT)\\.DLT")} → true, {@code
   * "X.DLT.DLT".matches(".*(?<!\\.DLT)\\.DLT")} → false.
   */
  @KafkaListener(topicPattern = ".*(?<!\\.DLT)\\.DLT", groupId = KafkaConsumerGroup.DLT_MONITOR)
  public void consume(
      ConsumerRecord<String, Object> record,
      @Header(name = KafkaHeaders.DLT_ORIGINAL_TOPIC, required = false) byte[] originalTopicHeader,
      @Header(name = KafkaHeaders.DLT_ORIGINAL_PARTITION, required = false)
          byte[] originalPartitionHeader,
      @Header(name = KafkaHeaders.DLT_ORIGINAL_OFFSET, required = false)
          byte[] originalOffsetHeader,
      @Header(name = KafkaHeaders.DLT_EXCEPTION_FQCN, required = false) byte[] exceptionFqcnHeader,
      @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false)
          byte[] exceptionMessageHeader,
      Acknowledgment ack) {

    // 헤더가 없으면(직접 발행 등) record의 값으로 최대 보존한다. 원본 토픽은 .DLT 접미사를 떼어 근사한다.
    String originalTopic = headerToString(originalTopicHeader, stripDltSuffix(record.topic()));
    int originalPartition = headerToInt(originalPartitionHeader, record.partition());
    long originalOffset = headerToLong(originalOffsetHeader, record.offset());
    String exceptionFqcn = headerToString(exceptionFqcnHeader, null);
    String exceptionMessage = headerToString(exceptionMessageHeader, null);

    String eventId = null;
    String eventType = null;
    String payload;
    Object value = record.value();
    if (value instanceof DomainEventEnvelope envelope) {
      eventId = envelope.eventId();
      eventType = envelope.eventType();
      payload = envelope.payload();
    } else {
      // value가 null이면 역직렬화 실패. springDeserializerExceptionValue 헤더에서 원본 바이트 추출을 시도한다.
      // null이 아니지만 envelope가 아닌 경우(드문 케이스)는 toString으로 폴백한다.
      payload = extractRawPayload(record);
    }

    Counter.builder(MetricNames.KAFKA_DLT).register(meterRegistry).increment();

    // #10: 사용자 영향 문자열(Kafka 메시지 유래)의 개행·탭을 sanitize해 로그 인젝션을 방지한다.
    log.error(
        "[DLT] 재시도 상한 초과 메시지 수신. 저장 시작. dltTopic={}, originalTopic={}, partition={}, offset={}, "
            + "eventType={}, eventId={}, exceptionFqcn={}, exceptionMessage={}",
        record.topic(),
        originalTopic,
        originalPartition,
        originalOffset,
        sanitize(eventType),
        sanitize(eventId),
        sanitize(exceptionFqcn),
        // #307: 로그 수집기 경로 PII 유출 방지 — 마스킹 후 로그 인젝션 sanitize 순으로 적용한다.
        sanitize(DltPayloadMasker.mask(exceptionMessage)));

    try {
      // #307: 저장 직전 PII를 보수적으로 마스킹한다(미래 PII 유입 방어). exceptionFqcn은 클래스명이라 마스킹 대상 아님.
      deadLetterRecordRepository.save(
          DeadLetterRecord.builder()
              .originalTopic(originalTopic)
              .originalPartition(originalPartition)
              .originalOffset(originalOffset)
              .messageKey(record.key())
              .eventType(eventType)
              .eventId(eventId)
              .payload(DltPayloadMasker.mask(payload))
              .exceptionFqcn(exceptionFqcn)
              .exceptionMessage(DltPayloadMasker.mask(exceptionMessage))
              .build());
    } catch (DataIntegrityViolationException e) {
      // #308: DIVE가 unique 위반(중복 수신)인지 다른 무결성 위반(길이초과 등)인지 구분한다.
      // existsBy 조회는 save가 자체 트랜잭션에서 롤백된 후 새 쿼리로 실행되므로 안전하다.
      boolean isDuplicate =
          deadLetterRecordRepository.existsByOriginalTopicAndOriginalPartitionAndOriginalOffset(
              originalTopic, originalPartition, originalOffset);
      if (!isDuplicate) {
        // unique 위반이 아닌 다른 무결성 위반(길이초과 등) = 실제 저장 실패. 유실 방지 위해 재시도.
        log.error(
            "[DLT] DeadLetterRecord 저장 실패(비중복 무결성 위반). 재시도한다. originalTopic={}, offset={}",
            originalTopic,
            originalOffset,
            e);
        throw e;
      }
      // 진짜 중복(멱등). 최초 수신 시 이미 저장했고 알림을 시도했으므로 재저장/재알림 없이 ack만 한다.
      // (최초 알림 실패 시 유실 가능은 수용된 트레이드오프 — #308)
      log.info(
          "[DLT] 중복 DeadLetterRecord 수신(멱등 스킵). originalTopic={}, partition={}, offset={},"
              + " eventType={}, eventId={}",
          originalTopic,
          originalPartition,
          originalOffset,
          eventType,
          eventId);
      ack.acknowledge();
      return;
    } catch (Exception e) {
      // 저장 실패는 ack하지 않고 예외를 던져 재시도되게 한다(실패 정책은 클래스 Javadoc 참조).
      log.error(
          "[DLT] DeadLetterRecord 저장 실패. 재시도를 위해 ack하지 않는다. originalTopic={}, offset={}",
          originalTopic,
          originalOffset,
          e);
      throw e;
    }

    // 저장 성공 후 알림. 알림 본문에 자유서식 예외 메시지를 포함하지 않는다(#6 — PII 유출 방지).
    // 알림 실패는 Notifier 내부에서 삼켜지며, 여기서도 방어적으로 감싼다.
    try {
      notifier.send(
          "[DLT] 재시도 상한 초과 메시지 적재",
          exceptionFqcn,
          buildMetadata(originalTopic, originalPartition, originalOffset, eventType, eventId));
    } catch (Exception e) {
      log.warn(
          "[DLT] 알림 전송 중 예외. 저장은 완료됨. originalTopic={}, offset={}",
          originalTopic,
          originalOffset,
          e);
    }

    ack.acknowledge();
  }

  /**
   * 역직렬화 실패(value=null)일 때 {@code springDeserializerExceptionValue} 헤더에서 원본 payload 바이트를 추출한다.
   *
   * <p>spring-kafka {@link SerializationUtils#getExceptionFromHeader} 헬퍼를 사용한다. 이 헬퍼는
   *
   * <ol>
   *   <li>헤더가 {@code DeserializationExceptionHeader} 인스턴스인지 검사(외부 조작 헤더 거부, CWE-502 방어)
   *   <li>{@code ObjectInputStream.resolveClass()}를 오버라이드해 첫 클래스를 {@link
   *       DeserializationException}으로 강제(가젯 체인 실행 차단)
   * </ol>
   *
   * 헤더 없거나 타입 검사 실패(foreign header) 또는 추출 실패 시, value가 non-null이면 {@code toString()}으로 폴백한다.
   */
  private String extractRawPayload(ConsumerRecord<String, Object> record) {
    DeserializationException deserEx =
        SerializationUtils.getExceptionFromHeader(
            record, KafkaUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER, LOG_ACCESSOR);
    if (deserEx != null && deserEx.getData() != null) {
      return new String(deserEx.getData(), StandardCharsets.UTF_8);
    }
    // 폴백: value가 null이 아닌 경우(역직렬화 성공이지만 envelope 타입이 아닌 경우) toString
    Object fallback = record.value();
    return (fallback != null) ? fallback.toString() : null;
  }

  private Map<String, String> buildMetadata(
      String originalTopic,
      int originalPartition,
      long originalOffset,
      String eventType,
      String eventId) {
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("originalTopic", originalTopic);
    metadata.put("partition", String.valueOf(originalPartition));
    metadata.put("offset", String.valueOf(originalOffset));
    metadata.put("eventType", String.valueOf(eventType));
    metadata.put("eventId", String.valueOf(eventId));
    return metadata;
  }

  private String stripDltSuffix(String topic) {
    if (topic != null && topic.endsWith(DLT_SUFFIX)) {
      return topic.substring(0, topic.length() - DLT_SUFFIX.length());
    }
    return topic;
  }

  /** 로그 인젝션 방지: 개행·탭 문자를 {@code _}로 대체한다. */
  private static String sanitize(String value) {
    return (value != null) ? value.replaceAll("[\\r\\n\\t]", "_") : null;
  }

  /** DLT 헤더는 byte[]로 올 수 있어 UTF-8 문자열로 안전 변환한다. */
  private String headerToString(byte[] header, String defaultValue) {
    return (header != null) ? new String(header, StandardCharsets.UTF_8) : defaultValue;
  }

  /** {@code DLT_ORIGINAL_PARTITION}은 4바이트 정수로 인코딩된다. */
  private int headerToInt(byte[] header, int defaultValue) {
    return (header != null && header.length >= Integer.BYTES)
        ? ByteBuffer.wrap(header).getInt()
        : defaultValue;
  }

  /** {@code DLT_ORIGINAL_OFFSET}은 8바이트 정수로 인코딩된다. */
  private long headerToLong(byte[] header, long defaultValue) {
    return (header != null && header.length >= Long.BYTES)
        ? ByteBuffer.wrap(header).getLong()
        : defaultValue;
  }
}
