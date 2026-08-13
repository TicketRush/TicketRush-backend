package com.ticketrush.global.eventpublisher;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.json.JsonConverter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * 이미 한 번 발행됐어야 할 이벤트를 <b>지정한 식별자로</b> 다시 발행한다 (#574).
 *
 * <p>{@link EventPublisher}와 나뉘어 있는 이유는 eventId의 출처가 다르기 때문이다. 신규 발행은 {@code
 * DomainEventEnvelope#of}가 매번 새 UUID를 만들지만, 재발행은 <b>같은 이벤트가 같은 식별자를 갖는 것</b>이 핵심이다. 수신 측 Inbox가
 * {@code (consumer_group, event_id)} 로 멱등을 판정하므로, 식별자가 매번 달라지면 재발행이 반복될 때마다 수신 측 트랜잭션이 다시 열린다.
 *
 * <p>이 능력을 {@link EventPublisher} 인터페이스에 넣지 않은 것은 의도적이다. 임의 eventId 발행은 잘못 쓰면 멱등이 깨지거나(랜덤화) 반대로
 * 이벤트가 영영 무시되므로(중복 식별자), 재발행이라는 용도가 타입에 드러나게 두어 오용 표면을 좁힌다.
 *
 * <p>트랜잭션 동기화를 하지 않고 <b>즉시 발행</b>한다. 재발행은 자신이 커밋할 DB 변경을 갖지 않으므로 커밋을 기다릴 대상이 없다.
 */
@Component
@ConditionalOnExpression(
    "'${app.event-publisher.type}' == 'kafka' or '${app.event-publisher.type}' == 'outbox'")
@RequiredArgsConstructor
public class EventReplayPublisher {

  private final EventMessageSender eventMessageSender;
  private final JsonConverter jsonConverter;

  /**
   * 저장된 상태에서 재구성한 이벤트를 지정 식별자로 발행한다.
   *
   * @param event 재구성한 도메인 이벤트
   * @param eventId 이 이벤트의 고정 식별자. 같은 대상이면 언제 호출해도 같은 값이어야 한다
   */
  public void republish(DomainEvent event, String eventId) {
    String payload = jsonConverter.serialize(event);
    DomainEventEnvelope envelope =
        new DomainEventEnvelope(
            eventId, event.eventName(), Instant.now(), event.topic(), payload, event.traceId());

    eventMessageSender.send(eventMessageSender.toMessage(envelope, event.key()), envelope);
  }

  /**
   * 재발행 대상의 고정 식별자를 만든다.
   *
   * <p>{@code inbox.event_id}가 {@code varchar(36)}이므로 임의 문자열이 아니라 UUID 형태로 만든다. 같은 {@code seed}는
   * 언제나 같은 UUID로 옮겨지고(이름 기반 UUID), 신규 발행이 쓰는 랜덤 UUID와 겹칠 확률은 무시할 수 있다.
   *
   * @param seed 재발행 대상을 유일하게 가리키는 문자열(용도 접두어 + 식별자)
   */
  public static String deterministicEventId(String seed) {
    return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
  }
}
