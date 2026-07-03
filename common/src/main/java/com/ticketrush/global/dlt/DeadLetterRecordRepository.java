package com.ticketrush.global.dlt;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@link DeadLetterRecord}에 대한 JPA 저장소. */
public interface DeadLetterRecordRepository extends JpaRepository<DeadLetterRecord, Long> {

  /** retention 대상 row를 벌크 삭제한다. {@code threshold} 이전에 저장(createdAt)된 row만 지운다. */
  @Modifying(clearAutomatically = true)
  @Query("DELETE FROM DeadLetterRecord d WHERE d.createdAt < :threshold")
  int deleteCreatedBefore(@Param("threshold") LocalDateTime threshold);
}
