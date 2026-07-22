package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceClearBookingOpenAtUseCase;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceOpenBookingUseCase;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.util.S3UploadUtils;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 서로소 컬럼을 쓰는 두 경로가 겹칠 때 stale 값이 서로를 덮지 않는지 검증한다(#459).
 *
 * <p>클래스 레벨 {@code @Transactional}을 쓰지 않는다. 이 시나리오는 "T1이 로드한 뒤 T2가 커밋하고 그 다음 T1이 커밋한다"는 순서 자체가 재현
 * 대상이라, 테스트 트랜잭션 하나로 감싸 롤백하는 기존 방식으로는 커밋 경계를 만들 수 없다. 대신 {@code TransactionTemplate} 두 개로 T2를
 * {@code REQUIRES_NEW}로 끼워 넣어 순서를 결정론적으로 고정한다 — 실제 경합은 스레드 타이밍이 아니라 커밋 순서에서 나오므로 스레드를 띄우지 않아도 동일한
 * 상황이며, 그 덕에 flaky하지 않다.
 *
 * <p>검증은 "UPDATE의 SET 절에 어떤 컬럼이 실렸는가"를 직접 보지 않고 <b>다른 트랜잭션이 커밋한 값이 살아남는가</b>로 한다. 레포에 SQL 문장을 단언할
 * 인프라가 없기도 하지만, SQL 문자열 비교는 취약한 반면 이 단언은 회귀가 실제로 사용자에게 드러나는 형태 그대로다. {@code Performance}에서
 * {@code @DynamicUpdate}를 떼면 아래 세 건이 모두 깨진다.
 *
 * <p>커밋이 실제로 남으므로 {@code @AfterEach}에서 물리 삭제한다. H2가 {@code DB_CLOSE_DELAY=-1}이라 컨텍스트를 공유하는 다른 테스트를
 * 오염시키고, {@code @SQLRestriction} 때문에 소프트 삭제된 행은 JPA로는 지울 수 없다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(
    exclude = {
      io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration.class,
      io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration.class
    })
class PerformanceConcurrentWriteTest {

  private static final String PATCHED_TITLE = "수정된 공연명";

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  @Autowired private PerformanceRepository performanceRepository;
  @Autowired private PerformanceClearBookingOpenAtUseCase performanceClearBookingOpenAtUseCase;
  @Autowired private PerformanceOpenBookingUseCase performanceOpenBookingUseCase;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  private TransactionTemplate outerTx;
  private TransactionTemplate separateTx;

  @BeforeEach
  void setUp() {
    outerTx = new TransactionTemplate(transactionManager);
    separateTx = new TransactionTemplate(transactionManager);
    separateTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM performance_images");
    jdbcTemplate.update("DELETE FROM performance_facilities");
    jdbcTemplate.update("DELETE FROM performance");
  }

  private Long saveCommitted(LocalDateTime bookingOpenAt) {
    return separateTx.execute(
        status ->
            performanceRepository
                .save(
                    Performance.builder()
                        .title("공연명")
                        .performer("출연진")
                        .genre(Genre.CONCERT)
                        .description("설명")
                        .showDate(LocalDate.now().plusDays(30))
                        .showTime(LocalTime.of(19, 0))
                        .durationMinutes(120)
                        .price(50000L)
                        .totalSeats(100)
                        .address("서울")
                        .bookingOpenAt(bookingOpenAt)
                        .build())
                .getId());
  }

  private Performance findCommitted(Long performanceId) {
    return separateTx.execute(
        status -> performanceRepository.findById(performanceId).orElseThrow());
  }

  /** PATCH가 공연명만 바꾸는 상황. bookingOpenAt은 전송되지 않았으므로(null) 엔티티에서 건드리지 않는다. */
  private void patchTitleOnly(Performance performance) {
    performance.update(PATCHED_TITLE, null, null, null, null, null, null, null, null, null, null);
  }

  @Test
  @DisplayName("PATCH가 로드한 뒤 예매 오픈 시각이 해제되면, PATCH 커밋이 해제를 되살리지 않는다")
  void patchDoesNotResurrectClearedBookingOpenAt() {
    Long performanceId = saveCommitted(LocalDateTime.now().plusDays(1));

    outerTx.executeWithoutResult(
        status -> {
          Performance loaded = performanceRepository.findById(performanceId).orElseThrow();
          patchTitleOnly(loaded);

          separateTx.executeWithoutResult(
              inner -> performanceClearBookingOpenAtUseCase.execute(performanceId));
        });

    Performance result = findCommitted(performanceId);
    assertThat(result.getBookingOpenAt()).isNull();
    assertThat(result.getTitle()).isEqualTo(PATCHED_TITLE);
  }

  @Test
  @DisplayName("상태 변경이 로드한 뒤 예매 오픈 시각이 해제되면, 상태 변경 커밋이 해제를 되살리지 않는다")
  void changeStatusDoesNotResurrectClearedBookingOpenAt() {
    Long performanceId = saveCommitted(LocalDateTime.now().plusDays(1));

    outerTx.executeWithoutResult(
        status -> {
          Performance loaded = performanceRepository.findById(performanceId).orElseThrow();
          loaded.changeStatus(PerformanceStatus.ON_SALE);

          separateTx.executeWithoutResult(
              inner -> performanceClearBookingOpenAtUseCase.execute(performanceId));
        });

    Performance result = findCommitted(performanceId);
    assertThat(result.getBookingOpenAt()).isNull();
    assertThat(result.getPerformanceStatus()).isEqualTo(PerformanceStatus.ON_SALE);
  }

  /**
   * 역방향 경합. 이전에는 스케줄러가 10초 주기로 재실행되며 다음 주기에 self-heal 되는 것에 기대고 있었다(PerformanceRepository Javadoc).
   * 이제는 PATCH가 status를 SET에 싣지 않으므로 되돌아가는 일 자체가 없다.
   */
  @Test
  @DisplayName("PATCH가 로드한 뒤 스케줄러가 예매를 오픈하면, PATCH 커밋이 상태를 되돌리지 않는다")
  void patchDoesNotRevertScheduledStatusTransition() {
    Long performanceId = saveCommitted(LocalDateTime.now().minusMinutes(1));

    outerTx.executeWithoutResult(
        status -> {
          Performance loaded = performanceRepository.findById(performanceId).orElseThrow();
          patchTitleOnly(loaded);

          separateTx.executeWithoutResult(inner -> performanceOpenBookingUseCase.execute());
        });

    Performance result = findCommitted(performanceId);
    assertThat(result.getPerformanceStatus()).isEqualTo(PerformanceStatus.ON_SALE);
    assertThat(result.getTitle()).isEqualTo(PATCHED_TITLE);
  }

  /**
   * {@code @DynamicUpdate}는 변경된 필드만 SET에 싣는데, updatedAt은 도메인 코드가 아니라 Auditing 리스너가
   * {@code @PreUpdate} 시점에 채운다. 그 값이 dirty 판정에 잡히지 않으면 updated_at이 갱신되지 않는 조용한 회귀가 된다. 비교 기준을 과거로
   * 직접 심어 시간 해상도에 의존하지 않고 판정한다.
   */
  @Test
  @DisplayName("동적 UPDATE에서도 updatedAt은 갱신된다")
  void updatedAtIsStillRefreshed() {
    Long performanceId = saveCommitted(null);
    LocalDateTime past = LocalDateTime.now().minusDays(1);
    jdbcTemplate.update(
        "UPDATE performance SET updated_at = ? WHERE performance_id = ?",
        Timestamp.valueOf(past),
        performanceId);

    outerTx.executeWithoutResult(
        status -> patchTitleOnly(performanceRepository.findById(performanceId).orElseThrow()));

    assertThat(findCommitted(performanceId).getUpdatedAt()).isAfter(past);
  }
}
