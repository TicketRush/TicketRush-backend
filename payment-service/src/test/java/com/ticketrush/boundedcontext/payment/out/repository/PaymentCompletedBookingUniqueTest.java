package com.ticketrush.boundedcontext.payment.out.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * uk_payment_completed_booking(STORED generated 컬럼 + unique 제약)이 실제 MySQL에서 동일 booking의 COMPLETED
 * 중복 생성을 막는지 검증한다(#422). 이 방어선은 수동 DDL로만 존재해 H2(ddl-auto)로는 재현되지 않으므로, #296 당시 예외 주입 단위 테스트({@code
 * PaymentFacadeTest})로 대체했던 실 DB 검증을 Testcontainers로 보강한다.
 *
 * <p>적용하는 DDL은 {@link Payment#getCompletedBookingId()} javadoc 런북(DDL의 SSOT)과 동일한 문장이므로, 이 테스트 통과가
 * 곧 운영 마이그레이션 런북의 MySQL 8.0 문법 검증을 겸한다. 런북이 바뀌면 이 테스트의 DDL도 함께 바꾼다.
 *
 * <p>Docker가 없는 환경에서는 이 테스트가 컨테이너 기동 실패로 깨진다. 의도된 fail-closed다 — silent skip을 허용하면 이 방어선이 CI에서 조용히
 * 사라져도 아무도 모른다(#422가 잡은 스냅샷 누락과 같은 패턴).
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=update")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 스레드별 커밋이 필요해 테스트 트랜잭션을 끈다
class PaymentCompletedBookingUniqueTest {

  /* prod(deploy/docker-compose.prod.yml)와 동일한 mysql:8.0 + utf8mb4_unicode_ci로 맞춘다. */
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer("mysql:8.0")
          .withDatabaseName("ticket_rush")
          .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @Autowired private PaymentRepository paymentRepository;
  @Autowired private DataSource dataSource;

  /* ddl-auto=update가 만든 일반 bigint 컬럼을 운영 런북과 동일한 DDL로 generated+unique 전환한다.
   * (운영 기존 DB와 같은 출발 상태 → 같은 마이그레이션 경로를 그대로 검증)
   * 적용 여부 판정도 런북 사전 점검 ②와 동일하게 information_schema로 한다 — JVM 상태(static 가드)에
   * 의존하면 클래스 재실행 시 새 컨테이너에 DDL이 누락되는 footgun이 생긴다. @BeforeAll을 쓰지 않는 이유:
   * @TestInstance(PER_CLASS)에서는 인스턴스 생성(=컨텍스트 로드)이 컨테이너 기동보다 먼저 일어난다. */
  @BeforeEach
  void applyManualDdlRunbook() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer constraintCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'payment' AND INDEX_NAME = 'uk_payment_completed_booking'",
            Integer.class);
    if (constraintCount != null && constraintCount > 0) {
      return;
    }
    jdbc.execute(
        "ALTER TABLE payment MODIFY COLUMN completed_booking_id BIGINT "
            + "GENERATED ALWAYS AS (CASE WHEN status = 'COMPLETED' THEN booking_id END) STORED");
    jdbc.execute(
        "ALTER TABLE payment ADD CONSTRAINT uk_payment_completed_booking "
            + "UNIQUE (completed_booking_id), ALGORITHM=INPLACE, LOCK=NONE");
  }

  @Test
  @DisplayName("동일 booking에 COMPLETED 결제가 이미 있으면 두 번째 COMPLETED 저장은 unique 제약으로 거부된다")
  void second_completed_payment_for_same_booking_is_rejected_by_unique_constraint() {
    Long bookingId = 100L;
    paymentRepository.saveAndFlush(completedPayment(bookingId, "pk-first-100"));

    assertThatThrownBy(
            () -> paymentRepository.saveAndFlush(completedPayment(bookingId, "pk-second-100")))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uk_payment_completed_booking");

    assertThat(paymentRepository.countByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("동일 booking에 서로 다른 paymentKey로 동시 confirm 저장 시 COMPLETED는 1건만 생성된다")
  void concurrent_completed_payments_for_same_booking_only_one_succeeds() throws Exception {
    Long bookingId = 200L;
    int threads = 2;
    CyclicBarrier barrier = new CyclicBarrier(threads);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger success = new AtomicInteger();
    AtomicInteger uniqueViolation = new AtomicInteger();
    AtomicReference<Exception> unexpected = new AtomicReference<>();

    ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      for (int i = 0; i < threads; i++) {
        String paymentKey = "pk-concurrent-200-" + i;
        executor.submit(
            () -> {
              try {
                barrier.await(); // 두 스레드가 동시에 INSERT를 시도하도록 정렬
                paymentRepository.saveAndFlush(completedPayment(bookingId, paymentKey));
                success.incrementAndGet();
              } catch (DataIntegrityViolationException e) {
                uniqueViolation.incrementAndGet();
              } catch (Exception e) {
                unexpected.compareAndSet(null, e); // 원인 진단용으로 보존해 아래 단언 메시지에 노출한다
              } finally {
                done.countDown();
              }
            });
      }
      assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
    }

    assertThat(unexpected.get()).as("unique 위반 외의 예기치 못한 예외").isNull();
    assertThat(success.get()).isEqualTo(1);
    assertThat(uniqueViolation.get()).isEqualTo(1);
    assertThat(paymentRepository.countByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("FAILED는 completed_booking_id가 NULL이라 동일 booking에 여러 건 누적돼도 제약에 걸리지 않는다")
  void failed_payments_do_not_collide_with_unique_constraint() {
    Long bookingId = 300L;
    paymentRepository.saveAndFlush(completedPayment(bookingId, "pk-completed-300"));
    paymentRepository.saveAndFlush(failedPayment(bookingId));
    paymentRepository.saveAndFlush(failedPayment(bookingId));

    List<Payment> payments =
        paymentRepository.findAll().stream()
            .filter(p -> p.getBookingId().equals(bookingId))
            .toList();
    assertThat(payments).hasSize(3);
    // generated 컬럼 값 검증: COMPLETED만 booking_id, FAILED는 NULL
    assertThat(payments)
        .filteredOn(p -> p.getStatus() == PaymentStatus.COMPLETED)
        .allSatisfy(p -> assertThat(p.getCompletedBookingId()).isEqualTo(bookingId));
    assertThat(payments)
        .filteredOn(p -> p.getStatus() == PaymentStatus.FAILED)
        .allSatisfy(p -> assertThat(p.getCompletedBookingId()).isNull());
  }

  private Payment completedPayment(Long bookingId, String paymentKey) {
    return Payment.builder()
        .bookingId(bookingId)
        .userId(1L)
        .seatId(1L)
        .provider(PaymentProvider.TOSS)
        .amount(10_000L)
        .status(PaymentStatus.COMPLETED)
        .paymentKey(paymentKey)
        .build();
  }

  private Payment failedPayment(Long bookingId) {
    return Payment.failed(
        bookingId, 1L, 1L, PaymentProvider.TOSS, 10_000L, "PAYMENT_400_001", "PG 거절", null, null);
  }
}
