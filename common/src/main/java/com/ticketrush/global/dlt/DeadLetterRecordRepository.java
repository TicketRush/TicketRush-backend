package com.ticketrush.global.dlt;

import org.springframework.data.jpa.repository.JpaRepository;

/** {@link DeadLetterRecord}에 대한 JPA 저장소. */
public interface DeadLetterRecordRepository extends JpaRepository<DeadLetterRecord, Long> {}
