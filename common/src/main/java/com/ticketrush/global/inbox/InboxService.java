package com.ticketrush.global.inbox;

import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.event.DomainEventEnvelope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbox 패턴 기반 중복 이벤트 처리 방지 진입점 (#110).
 *
 * <p>컨슈머(리스너)에는 트랜잭션이 없고 비즈니스 트랜잭션은 usecase에서 시작하므로, 이 서비스가 {@link Transactional}로 트랜잭션을 열고 비즈니스
 * 로직을 콜백으로 받아 <b>같은 트랜잭션(REQUIRED)에 조인</b>시킨다. 그 결과 "비즈니스 처리"와 "Inbox 기록"이 하나의 원자 단위로 커밋/롤백된다.
 */
@Service
@RequiredArgsConstructor
public class InboxService {

  private final InboxRepository inboxRepository;
  private final MeterRegistry meterRegistry;

  /**
   * 해당 컨슈머 그룹이 처음 보는 이벤트이면 {@code business}를 실행하고 Inbox에 기록한 뒤 {@code true}를, 이미 처리한 이벤트이면 아무것도 하지
   * 않고 {@code false}를 반환한다.
   *
   * <ul>
   *   <li>{@code business}가 던진 예외는 잡지 않고 전파한다 → 트랜잭션 롤백(Inbox 미기록)되어 호출자(리스너)의 #269 에러 분기가 그대로
   *       동작한다. 실패한 처리는 기록되지 않으므로 재전달 시 재처리된다. 비즈니스가 던진 일반 {@code DataIntegrityViolationException}도
   *       그대로 전파돼 #269 분기(일시→재시도→DLT)로 보존된다.
   *   <li>동시 중복 수신으로 {@code (consumer_group, event_id)} unique가 충돌하면 {@code saveAndFlush}의 {@code
   *       DataIntegrityViolationException}을 {@link DuplicateEventException}으로 변환해 던진다. 이 예외만 호출자가
   *       멱등(중복) 정상으로 간주해 ack 하고, 그 외 무결성 위반과는 구분된다.
   * </ul>
   *
   * @param consumerGroup 처리 주체 컨슈머 그룹({@code KafkaConsumerGroup} 상수)
   * @param envelope 수신 이벤트 봉투(멱등 식별자 {@code eventId} 제공)
   * @param business 최초 수신일 때만 실행할 비즈니스 로직(같은 트랜잭션에 조인)
   * @return 이번에 처리했으면 {@code true}, 이미 처리된 중복이면 {@code false}
   * @throws DuplicateEventException 동시 중복 수신으로 inbox unique가 경합했을 때
   */
  @Transactional
  public boolean runIfFirst(String consumerGroup, DomainEventEnvelope envelope, Runnable business) {
    if (inboxRepository.existsByConsumerGroupAndEventId(consumerGroup, envelope.eventId())) {
      Counter.builder(MetricNames.KAFKA_INBOX)
          .tag(MetricNames.TAG_CONSUMER_GROUP, consumerGroup)
          .tag(MetricNames.TAG_RESULT, MetricNames.RESULT_DUPLICATE)
          .register(meterRegistry)
          .increment();
      return false;
    }

    business.run();

    try {
      // saveAndFlush로 unique 위반을 트랜잭션 안에서 즉시 표면화해, 동시 중복의 패자가 커밋 전에 롤백되도록 한다.
      inboxRepository.saveAndFlush(
          InboxEntity.of(consumerGroup, envelope.eventId(), envelope.eventType()));
    } catch (DataIntegrityViolationException e) {
      // inbox unique 경합(동시 중복)만 전용 예외로 변환한다. 비즈니스가 던진 DIVE는 여기 오지 않고 그대로 전파된다.
      // 롤백-only 상태에서 정상 반환하면 커밋 시 UnexpectedRollbackException이 나므로 반드시 예외로 롤백시킨다.
      throw new DuplicateEventException(consumerGroup, envelope.eventId(), e);
    }
    Counter.builder(MetricNames.KAFKA_INBOX)
        .tag(MetricNames.TAG_CONSUMER_GROUP, consumerGroup)
        .tag(MetricNames.TAG_RESULT, MetricNames.RESULT_PROCESSED)
        .register(meterRegistry)
        .increment();
    return true;
  }
}
