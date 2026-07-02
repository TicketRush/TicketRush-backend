package com.ticketrush.global.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link OutboxEntity}에 대한 JPA 저장소.
 *
 * <p>이 이슈(#101) 범위에서는 발행 시 Outbox row를 저장하는 {@code save()}만 사용한다. 폴링 relay용 조회/상태 전이 쿼리는 후속
 * 이슈(#102)에서 추가된다.
 */
public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {}
