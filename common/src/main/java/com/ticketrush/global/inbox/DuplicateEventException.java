package com.ticketrush.global.inbox;

/**
 * 동시 중복 수신으로 Inbox의 {@code (consumer_group, event_id)} unique가 경합했음을 알리는 신호 예외 (#110).
 *
 * <p>{@link InboxService#runIfFirst}가 inbox 기록({@code saveAndFlush}) 중 {@code
 * DataIntegrityViolationException}을 만나면 트랜잭션을 깨끗이 롤백시키기 위해 이 예외로 변환해 던진다(롤백-only 상태에서 정상 반환하면 커밋
 * 시점에 {@code UnexpectedRollbackException}이 나기 때문).
 *
 * <p>리스너는 이 예외만 "멱등(중복) 정상"으로 간주해 ack 한다. 비즈니스 로직이 던진 일반 {@code DataIntegrityViolationException}은 이
 * 예외로 감싸지 않으므로 #269 표준 분기(일시→재시도→DLT)로 흘러 보존된다.
 */
public class DuplicateEventException extends RuntimeException {

  public DuplicateEventException(String consumerGroup, String eventId, Throwable cause) {
    super("Duplicate event: consumerGroup=" + consumerGroup + ", eventId=" + eventId, cause);
  }
}
