package com.ticketrush.boundedcontext.seat.app.facade;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.seat.app.mapper.SeatMapper;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatHoldUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatLockUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatUnlockUseCase;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatMapCacheRepository;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.types.SeatStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 동일 좌석 고동시성에서 oversell이 0건인지 실 MySQL·Redis로 검증한다(#344).
 *
 * <p><b>이 테스트가 만드는 락 경합은 프로덕션에서 실제로 일어나지 않는다.</b> 운영에서 좌석 홀드를 부르는 스레드는 Kafka 컨슈머 하나뿐이라({@code
 * KafkaConfig}의 컨테이너 팩토리에 {@code setConcurrency}가 없고 {@code spring.kafka.listener.concurrency}도 없어
 * 기본 1) Redisson 락은 경합하지 않는다. {@code RLock}은 (clientUUID:threadId) 기준 재진입 락이라 같은 스레드의 {@code
 * tryLock}은 실패 대신 재진입 성공한다. 즉 {@code SEAT_LOCK_CONTENTION}은 운영에서 구조적으로 0이며, 부하 테스트로는 이 카운터를 올릴 수
 * 없다(측정 결과와 근거는 docs/load-test-guide.md §10).
 *
 * <p>그래서 두 테스트가 서로 다른 것을 검증한다.
 *
 * <ol>
 *   <li>스레드를 갈라 락 경합을 <b>인위적으로</b> 만들었을 때 {@code SeatFacade}의 분기(차단·보상·카운터)가 설계대로 도는가 — 컨슈머 동시성을 올릴
 *       때를 대비한 회귀 그물.
 *   <li>Redis 락이 유실돼도(#426, ADR 0008) DB {@code @Version}이 중복 HOLD를 막는가 — <b>운영의 실제 방어선</b>(#427).
 * </ol>
 *
 * <p>{@code TransactionTemplate}으로 감싸는 이유는 {@code runIfFirst}의 트랜잭션 경계를 재현하기 위해서다. 없으면 (a) {@code
 * OutboxEventPublisher}가 활성 트랜잭션을 요구해 보상 경로가 터지고, (b) {@code SeatHoldUseCase}의 success 카운터가
 * afterCommit을 건너뛰고 즉시 증가해 #427이 막은 버그(롤백된 HOLD를 success로 집계)가 되살아난다.
 *
 * <p>{@code @DataJpaTest} 슬라이스를 쓰는 이유는 {@code @Configuration}을 스캔하지 않기 때문이다 — {@code KafkaConfig},
 * Redisson 오토컨피그, {@code @EnableScheduling}이 전부 안 뜨므로 리스너·스케줄러를 끄는 설정이 따로 필요 없다.
 *
 * <p>Docker가 없는 환경에서는 컨테이너 기동 실패로 깨진다. 의도된 fail-closed다 — silent skip을 허용하면 이 방어선이 CI에서 조용히 사라져도
 * 아무도 모른다(#422와 동일한 판단).
 */
@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=update",
      // 낙관적 락 테스트가 THREADS개 트랜잭션을 배리어에서 동시에 열어둔 채 대기시킨다.
      // 기본 풀(10)이면 커넥션을 못 받은 스레드가 배리어에 도달하지 못해 전원이 굶는다.
      "spring.datasource.hikari.maximum-pool-size=25"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 스레드별 커밋이 필요해 테스트 트랜잭션을 끈다
class SeatHoldConcurrencyTest {

  private static final int THREADS = 20;

  /* prod(deploy/docker-compose.prod.yml)와 동일한 mysql:8.0 + utf8mb4_unicode_ci로 맞춘다. */
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer("mysql:8.0")
          .withDatabaseName("ticket_rush")
          .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

  /* 로컬 스택(docker-compose.yml)과 같은 태그. Redisson이 이 컨테이너를 직접 가리킨다. */
  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  // Redis는 @DynamicPropertySource로 넘기지 않는다. @DataJpaTest 슬라이스엔 Redisson 오토컨피그가
  // 없어 spring.data.redis.* 를 넣어도 클라이언트가 만들어지지 않는다. setUp에서 직접 만든다.

  @Autowired private SeatRepository seatRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private RedissonClient redissonClient;
  private TransactionTemplate transactionTemplate;
  private SimpleMeterRegistry meterRegistry;
  private AtomicInteger compensations;
  private SeatFacade seatFacade;
  private SeatHoldUseCase seatHoldUseCase;

  @BeforeEach
  void setUp() {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort());
    redissonClient = Redisson.create(config);

    transactionTemplate = new TransactionTemplate(transactionManager);
    meterRegistry = new SimpleMeterRegistry(); // 컨벤션: 메트릭은 실물을 주입해 카운트를 단언한다
    compensations = new AtomicInteger();

    seatHoldUseCase =
        new SeatHoldUseCase(
            seatRepository,
            // SSE 전송·좌석맵 캐시 무효화는 이 테스트의 관심사가 아니다. SeatStatusEventSender가
            // 인터페이스라 람다로 끝나고, 캐시 리포지토리는 no-op mock으로 채운다.
            new SeatStatusEventPublisher(
                event -> {},
                Mappers.getMapper(SeatMapper.class),
                org.mockito.Mockito.mock(SeatMapCacheRepository.class),
                meterRegistry),
            meterRegistry);

    // ponytail: SeatFacade의 생성자 13개 중 홀드 경로가 쓰는 4개만 채우고 나머지 9개는 null로 둔다.
    //           @Import + @MockitoBean 7개보다 짧고, 타입이 전부 달라 오배치는 컴파일러가 잡는다.
    //           EventPublisher도 람다 — Outbox 테이블도 Kafka도 필요 없고 호출 횟수가 곧 차단 수다
    //           (Mockito verify는 멀티스레드 카운팅에서 신뢰도가 낮다).
    seatFacade =
        new SeatFacade(
            null,
            null,
            null,
            null,
            null,
            null,
            seatHoldUseCase,
            new SeatLockUseCase(redissonClient, meterRegistry),
            new SeatUnlockUseCase(redissonClient),
            null,
            null,
            event -> compensations.incrementAndGet(),
            null,
            null);
  }

  @AfterEach
  void tearDown() {
    if (redissonClient != null) {
      redissonClient.shutdown();
    }
  }

  @Test
  @DisplayName("동일 좌석에 20개 스레드가 동시 선점을 시도해도 HOLD는 1건만 생기고 나머지는 락 경합으로 차단된다")
  void concurrent_hold_on_same_seat_yields_single_hold() throws Exception {
    // given
    Long seatId = seatRepository.save(availableSeat("A-1")).getId();
    CyclicBarrier barrier = new CyclicBarrier(THREADS);
    CountDownLatch done = new CountDownLatch(THREADS);
    AtomicReference<Exception> unexpected = new AtomicReference<>();

    // when: 스레드를 갈라야 RLock 재진입이 깨지고 tryLock이 실제로 false를 반환한다
    ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    try {
      for (int i = 0; i < THREADS; i++) {
        long bookingId = i;
        executor.submit(
            () -> {
              try {
                barrier.await(); // 모든 스레드가 동시에 락을 노리도록 정렬
                // runIfFirst의 트랜잭션 경계 재현 (없으면 보상 발행이 IllegalStateException)
                transactionTemplate.executeWithoutResult(
                    status ->
                        seatFacade.tryLockSeat(bookingId, "BOOK-" + bookingId, seatId, bookingId));
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

    // then
    assertThat(unexpected.get()).as("예기치 못한 예외").isNull();

    Seat seat = seatRepository.findById(seatId).orElseThrow();
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.HOLD); // oversell 0: 승자는 정확히 1
    assertThat(seat.getVersion()).isEqualTo(1L); // HOLD를 쓴 트랜잭션은 하나뿐

    assertThat(holdCounter(MetricNames.RESULT_SUCCESS)).isEqualTo(1);
    // 승자는 락을 TTL(5분)까지 쥐고 있어(성공 경로엔 unlock이 없다) 패자는 전원 tryLock에서 걸린다.
    // → DB 체크까지 도달하지 못하므로 unavailable은 0이고 contention이 정확히 N-1이다.
    assertThat(contentionCounter()).isEqualTo(THREADS - 1);
    assertThat(holdCounter(MetricNames.RESULT_UNAVAILABLE)).isEqualTo(0);
    assertThat(compensations.get()).isEqualTo(THREADS - 1); // 차단된 전원이 보상 이벤트를 받는다
  }

  @Test
  @DisplayName("Redis 락이 유실돼 전원이 같은 좌석을 동시에 읽어도 DB 낙관적 락이 중복 HOLD를 막는다")
  void optimistic_lock_blocks_duplicate_hold_when_redis_lock_is_lost() throws Exception {
    // given: Redisson 락을 통째로 건너뛴다 = 락 키가 유실된 최악(#426, ADR 0008)을 재현한다
    Long seatId = seatRepository.save(availableSeat("B-1")).getId();
    CyclicBarrier startBarrier = new CyclicBarrier(THREADS);
    CyclicBarrier beforeCommitBarrier = new CyclicBarrier(THREADS);
    CountDownLatch done = new CountDownLatch(THREADS);
    AtomicInteger held = new AtomicInteger(); // 커밋까지 성공한 HOLD
    AtomicInteger unavailable = new AtomicInteger(); // isAvailable() 차단
    AtomicInteger optimisticConflict = new AtomicInteger(); // 커밋 시점 @Version 충돌
    AtomicReference<Exception> unexpected = new AtomicReference<>();

    // when
    ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    try {
      for (int i = 0; i < THREADS; i++) {
        long n = i;
        executor.submit(
            () -> {
              try {
                startBarrier.await();
                boolean[] transitioned = new boolean[1];
                transactionTemplate.executeWithoutResult(
                    status -> {
                      transitioned[0] =
                          seatHoldUseCase.execute(
                              seatId, LocalDateTime.now().plusMinutes(5), "BOOK-" + n);
                      // 전원이 AVAILABLE을 읽고 hold()를 마친 뒤에야 커밋시킨다. 이 배리어가 없으면
                      // 승자가 남들의 findById 전에 커밋해 나머지가 isAvailable()에서 걸러지고,
                      // 낙관적 락이 한 번도 발화하지 않은 채 테스트가 통과한다(= @Version을 지워도
                      // 초록불). 배리어로 read-check-write 구간을 겹쳐 충돌을 결정적으로 만든다.
                      awaitQuietly(beforeCommitBarrier);
                    });
                // 여기 도달 = 커밋 성공. 카운트는 롤백이 불가능해진 뒤에만 올린다.
                if (transitioned[0]) {
                  held.incrementAndGet();
                } else {
                  unavailable.incrementAndGet();
                }
              } catch (ObjectOptimisticLockingFailureException e) {
                // 커밋(flush) 시점 @Version 충돌 → 롤백. 운영에서는 리스너가 일시 오류로 분류해
                // Kafka 재시도로 수렴하고, 재시도 때는 이미 HOLD라 보상 이벤트가 발행된다(#427).
                optimisticConflict.incrementAndGet();
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

    // then: 락이 없어도 DB가 최종 차단한다
    assertThat(unexpected.get()).as("낙관적 락 충돌 외의 예기치 못한 예외").isNull();

    Seat seat = seatRepository.findById(seatId).orElseThrow();
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.HOLD);
    assertThat(seat.getVersion()).isEqualTo(1L); // 커밋에 성공한 HOLD는 하나뿐 = oversell 0
    assertThat(held.get()).as("커밋된 HOLD").isEqualTo(1);
    // 이 테스트가 증명하려는 방어선이 실제로 발화했는지 고정한다. 이걸 unavailable과 합산해 세면
    // 낙관적 락이 0번 발화해도 통과해 @Version 회귀를 놓친다.
    assertThat(optimisticConflict.get()).as("커밋 시점 @Version 충돌").isEqualTo(THREADS - 1);
    assertThat(unavailable.get()).as("isAvailable() 차단 — 전원이 겹쳐 읽으므로 0").isZero();
    // 롤백된 HOLD는 success로 세지 않는다(#427: afterCommit 증가)
    assertThat(holdCounter(MetricNames.RESULT_SUCCESS)).isEqualTo(1);
  }

  /* 트랜잭션 람다 안에서는 checked 예외를 던질 수 없어 배리어 대기를 감싼다. */
  private static void awaitQuietly(CyclicBarrier barrier) {
    try {
      barrier.await(20, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("배리어 대기 실패", e);
    }
  }

  /* Seat는 seatLayoutId/performanceId가 plain Long이라 FK가 없다 → 좌석만 단독 저장할 수 있다. */
  private Seat availableSeat(String seatNumber) {
    return Seat.builder()
        .seatLayoutId(1L)
        .performanceId(1L)
        .seatNumber(seatNumber)
        .seatStatus(SeatStatus.AVAILABLE)
        .build();
  }

  private double holdCounter(String result) {
    Counter counter =
        meterRegistry.find(MetricNames.SEAT_HOLD).tag(MetricNames.TAG_RESULT, result).counter();
    return counter == null ? 0 : counter.count();
  }

  private double contentionCounter() {
    Counter counter = meterRegistry.find(MetricNames.SEAT_LOCK_CONTENTION).counter();
    return counter == null ? 0 : counter.count();
  }
}
