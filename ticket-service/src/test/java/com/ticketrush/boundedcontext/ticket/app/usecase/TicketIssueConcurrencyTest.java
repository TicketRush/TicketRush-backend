package com.ticketrush.boundedcontext.ticket.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.ticket.app.mapper.TicketMapperImpl;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketTokenGenerator;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketTokenHasher;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxRepository;
import com.ticketrush.global.inbox.InboxService;
import com.ticketrush.global.jpa.config.JpaConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 동일 결제확정 이벤트의 동시 재전달(at-least-once)에서 Inbox({@code uk_inbox_group_event}) + 티켓 {@code booking_id}
 * unique 2중 방어가 이중 발급을 막는지 실 MySQL로 검증한다(#347). 실물 {@link InboxService} + {@link
 * TicketIssueUseCase} 조합으로, 스레드마다 {@code runIfFirst}가 독립 트랜잭션을 연다.
 *
 * <p>경합 패자의 결과는 스레드 타이밍에 따라 갈린다 — 승자 커밋 후 진입하면 inbox fast-path({@code false}), 승자 커밋 전 진입하면 티켓
 * {@code booking_id} unique 대기 후 {@link DataIntegrityViolationException}(전파·롤백, 재소비 시 self-heal —
 * {@code TicketIssueUseCase} javadoc). 따라서 개별 경로 횟수가 아니라 "성공 정확히 1 + 나머지 전부 차단 + 티켓 1건"을 단언한다.
 *
 * <p>Docker가 없는 환경에서는 컨테이너 기동 실패로 깨진다. 의도된 fail-closed다({@code PaymentCompletedBookingUniqueTest}와
 * 동일 정책).
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 스레드별 커밋이 필요해 테스트 트랜잭션을 끈다
@Import({
  JpaConfig.class,
  InboxService.class,
  TicketIssueUseCase.class,
  TicketTokenGenerator.class,
  TicketTokenHasher.class,
  TicketMapperImpl.class,
  SimpleMeterRegistry.class
})
class TicketIssueConcurrencyTest {

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

  @Autowired private InboxService inboxService;
  @Autowired private TicketIssueUseCase ticketIssueUseCase;
  @Autowired private TicketRepository ticketRepository;
  @Autowired private InboxRepository inboxRepository;
  @Autowired private MeterRegistry meterRegistry;

  private static final int THREADS = 10;
  private static final int SEQUENTIAL_REDELIVERIES = 5;

  private DomainEventEnvelope envelope(String eventId) {
    return new DomainEventEnvelope(
        eventId,
        "PaymentConfirmedEvent",
        Instant.now(),
        "payment-confirmed-topic",
        "payload",
        null);
  }

  @Test
  @DisplayName("동일 결제확정 이벤트를 N스레드 동시 재주입해도 티켓은 정확히 1건 발급된다")
  void concurrent_redelivery_issues_exactly_one_ticket() throws Exception {
    Long bookingId = 347L;
    DomainEventEnvelope envelope = envelope("evt-347"); // 같은 eventId = 같은 이벤트의 재전달

    CyclicBarrier barrier = new CyclicBarrier(THREADS);
    CountDownLatch done = new CountDownLatch(THREADS);
    AtomicInteger processed = new AtomicInteger(); // runIfFirst == true (승자)
    AtomicInteger skippedByInbox = new AtomicInteger(); // runIfFirst == false (inbox fast-path)
    AtomicInteger uniqueLoser = new AtomicInteger(); // unique 경합 패자(예외·롤백)
    AtomicReference<Exception> unexpected = new AtomicReference<>();

    ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    try {
      for (int i = 0; i < THREADS; i++) {
        executor.submit(
            () -> {
              try {
                barrier.await(); // 전 스레드가 동시에 재주입하도록 정렬
                boolean first =
                    inboxService.runIfFirst(
                        KafkaConsumerGroup.TICKET,
                        envelope,
                        () -> ticketIssueUseCase.execute(bookingId, 1L));
                if (first) {
                  processed.incrementAndGet();
                } else {
                  skippedByInbox.incrementAndGet();
                }
              } catch (DataIntegrityViolationException | DuplicateEventException e) {
                uniqueLoser.incrementAndGet();
              } catch (Exception e) {
                unexpected.compareAndSet(null, e); // 원인 진단용으로 보존해 아래 단언 메시지에 노출한다
              } finally {
                done.countDown();
              }
            });
      }
      assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
    }

    assertThat(unexpected.get()).as("unique 경합 외의 예기치 못한 예외").isNull();
    assertThat(processed.get()).isEqualTo(1);
    assertThat(skippedByInbox.get() + uniqueLoser.get()).isEqualTo(THREADS - 1);
    assertThat(ticketRepository.count()).as("이중 발급 0건").isEqualTo(1);
    assertThat(
            inboxRepository.existsByConsumerGroupAndEventId(
                KafkaConsumerGroup.TICKET, envelope.eventId()))
        .isTrue();

    // 2차: 순차 재전달(경합 패자의 재소비 상황) — 전부 inbox fast-path로 차단된다
    for (int i = 0; i < SEQUENTIAL_REDELIVERIES; i++) {
      boolean first =
          inboxService.runIfFirst(
              KafkaConsumerGroup.TICKET, envelope, () -> ticketIssueUseCase.execute(bookingId, 1L));
      assertThat(first).isFalse();
    }
    assertThat(ticketRepository.count()).isEqualTo(1);

    // 메트릭 검증: 중복 차단율 = duplicate / (duplicate + processed) — #347 측정 산식과 동일
    double duplicate =
        meterRegistry
            .get(MetricNames.KAFKA_INBOX)
            .tag(MetricNames.TAG_CONSUMER_GROUP, KafkaConsumerGroup.TICKET)
            .tag(MetricNames.TAG_RESULT, MetricNames.RESULT_DUPLICATE)
            .counter()
            .count();
    double ok =
        meterRegistry
            .get(MetricNames.KAFKA_INBOX)
            .tag(MetricNames.TAG_CONSUMER_GROUP, KafkaConsumerGroup.TICKET)
            .tag(MetricNames.TAG_RESULT, MetricNames.RESULT_PROCESSED)
            .counter()
            .count();
    assertThat(ok).isEqualTo(1.0);
    assertThat(duplicate).isEqualTo(skippedByInbox.get() + (double) SEQUENTIAL_REDELIVERIES);
    // 차단율 = duplicate/(duplicate+processed). 위 두 정확 단언으로 산식 성립이 이미 보장되므로
    // 비율 자체는 별도 단언하지 않는다(정확 단언에서 파생되는 항진식이라 검증력이 없다).
  }
}
