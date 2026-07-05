# Kafka Event Guide

## 1. Architecture & Configuration Overview (`KafkaConfig`)
본 프로젝트의 Kafka 설정은 마이크로서비스 환경에서 **메시지 유실 방지(Zero Data Loss)** 와 **장애 격리/복구(Resilience)** 에 초점을 맞추어 설계되었습니다.

### 1.1. Producer (발행자) 주요 정책
* **신뢰성 100% 보장:** `acks=all` 및 `enable.idempotence=true`를 적용하여 카프카 브로커에 메시지가 완벽히 복제되었는지 확인하며, 네트워크 재시도 시 발생할 수 있는 중복 발행을 원천 차단합니다.
* **재시도 및 타임아웃:** 전송 실패 시 최대 3회(`PRODUCER_RETRIES`) 재시도하며, 최대 120초(`DELIVERY_TIMEOUT_MS`)까지 전송을 보장하기 위해 대기합니다.
* **Envelope 패턴 적용:** 모든 이벤트는 `DomainEventEnvelope`라는 규격화된 객체로 포장되어 전송되며, 내부 페이로드(데이터)는 JSON 형태로 안전하게 직렬화됩니다.

### 1.2. Consumer (수신자) 및 에러 핸들링 정책
* **수동 커밋 (Manual Commit):** `AckMode.MANUAL_IMMEDIATE`를 설정하여, 컨슈머가 이벤트 처리를 완벽하게 끝마쳤을 때만 오프셋을 커밋합니다. (처리 도중 서버가 죽어도 데이터 유실 없음)
* **지수 백오프(Exponential Backoff) 재시도:** 일시적인 장애(예: DB 락, 일시적 네트워크 단절) 발생 시 즉시 실패 처리하지 않고, 점진적으로 대기 시간을 늘려가며 최대 5회 재시도합니다 (초기 1초 → 최대 60초 대기).
* **DLT (Dead Letter Topic) 자동 라우팅:** 최대 재시도 횟수를 초과하거나, 역직렬화 실패(`DeserializationException`) 같은 논리적 오류 발생 시, 해당 메시지를 유실시키지 않고 `[원본토픽명].DLT` 토픽으로 자동 격리합니다. 이를 통해 정상 메시지 처리가 지연되는 것을 막고 실패한 메시지만 추후 분석할 수 있습니다.

---

## 2. 컨슈머 에러 처리 표준 (#269)

`@KafkaListener` 리스너가 예외를 만났을 때 **커밋(ack)** 할지 **재시도→DLT에 위임(re-throw)** 할지를 통일한 규약이다. §1.2의 인프라(재시도→DLT) 정책은 **리스너가 예외를 다시 던져야만** 작동하므로, 이 표준이 그 진입 조건을 정의한다.

**대원칙 — fail-safe = 메시지 보존.** 애매하면 re-throw 하여 재시도→DLT로 보존한다. 예외를 삼켜 유실시키지 않는다.

### 2.1. 예외 분류와 처리 (ack vs re-throw 결정표)

| 분류 | 판정 기준 | 로그 | 오프셋 | 근거 |
|------|-----------|------|--------|------|
| **영구(permanent)** | cause 체인에 `BusinessException` 존재 (비즈니스 규칙 위반, payload 역직렬화 `json.DeserializationException` 등) | `[CRITICAL]` error | **ack** | 재시도해도 결과 불변 → 커밋해 파티션 블로킹 방지. 복구는 CRITICAL 로그·모니터링(#50)에 위임 |
| └ 그중 **예상된 상태충돌** | `BusinessException` 이면서 **화이트리스트**(`KafkaConsumerErrorPolicy.EXPECTED_CONFLICTS`: `SEAT_NOT_AVAILABLE`·`SEAT_ALREADY_LOCKED`·`PAYMENT_ALREADY_COMPLETED`·`TICKET_ALREADY_USED`·`TICKET_NOT_USABLE`·`BOOKING_CANCEL_NOT_ALLOWED`)에 속함 | `warn` (CRITICAL 아님) | **ack** | 재수신·순서로 자연 발생하는 멱등 상태 → 알림 피로 방지. **모든 409를 정상으로 보지 않는다** — `BOOKING_SEAT_MISMATCH`·`BOOKING_EXPIRED`·`BOOKING_CONFIRM_NOT_ALLOWED`(결제됐으나 확정 불가)는 화이트리스트에서 제외해 `[CRITICAL]`로 남긴다 |
| **일시(transient)** | 위에 해당하지 않는 모든 예외 (Spring `DataAccessException`·락 타임아웃, Redis 오류, `RetriableException`, 미분류 `RuntimeException`) | `warn` | **ack 안 함 → re-throw** | 재시도하면 성공할 수 있음 → 재시도 5회 후 DLT로 보존 |
| **예상된 중복(DAO)** | `DataIntegrityViolationException`(unique 위반=중복 등록) 등 리스너별로 명시 처리 | `info` | **ack** | 멱등 결과(정상). Inbox 동시 중복 경합(`DuplicateEventException`)이 대표 사례 → §5.3. 단 재시도가 필요한 경우(예: 토큰 해시 충돌 재생성)는 리스너 맥락에 맞게 예외 처리 |

분류는 공통 헬퍼 **`com.ticketrush.global.event.KafkaConsumerErrorPolicy`** 로 판정한다: `isPermanent(Throwable)`, `isExpectedConflict(Throwable)`.

### 2.2. 표준 리스너 템플릿

```java
@KafkaListener(topics = XxxEvent.TOPIC, groupId = KafkaConsumerGroup.XXX)
public void handleXxx(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {
  XxxEvent event = null;
  try {
    event = jsonConverter.deserialize(envelope.payload(), XxxEvent.class);
    usecase.execute(...);
    ack.acknowledge();                       // 성공 시에만 커밋
  } catch (Exception e) {
    if (KafkaConsumerErrorPolicy.isPermanent(e)) {
      if (KafkaConsumerErrorPolicy.isExpectedConflict(e)) {
        log.warn("... 예상된 상태충돌(멱등 처리). eventId: {}", envelope.eventId(), e);
      } else {
        log.error("[CRITICAL] ... 확인이 필요합니다. eventId: {}", envelope.eventId(), e);
      }
      ack.acknowledge();                     // 영구 실패: 재시도 무의미 → 커밋
    } else {
      log.warn("... 일시적 오류. 재시도합니다. eventId: {}", envelope.eventId(), e);
      throw e;                               // 일시 실패: 재시도→DLT 위임 (ack 안 함)
    }
  }
}
```

- **ack ⊻ re-throw 상호배타:** `MANUAL_IMMEDIATE`에서 한 경로는 ack 하거나 예외를 전파하거나 **둘 중 하나만** 한다(둘 다 금지). 예전의 `finally`-무조건-ack 패턴은 일시 예외까지 삼켜 유실시키므로 금지한다.
- **중복 수신 방지가 필요한 비멱등 리스너**는 처리를 **Inbox(`runIfFirst`)로 감싼다**(→ **§5**). 동시 중복 경합 시 `DuplicateEventException`을 `ack`로 처리하고, 일시 실패는 전파해 롤백(Inbox 미기록)→재처리한다. (과거 seat `BookingCreatedEventListener`의 Redis SETNX 멱등키 방식은 #313에서 Inbox 표준으로 통합됨.)

### 2.3. 주의사항

- **크로스서비스 HTTP 호출은 `isPermanent`로 분류하지 않는다.** RestClient 호출 실패는 `BusinessException`이 아니라 `HttpClientErrorException`(4xx)/`HttpServerErrorException`(5xx)/`ResourceAccessException`(네트워크)으로 도착한다. **HTTP 상태코드 기반으로 직접 분기**한다: 4xx(결정적)→진행(ack)하되 **409(이미 처리된 중복)만 warn, 그 외 4xx(401·404 등 설정/요청 오류)는 `[CRITICAL]`**, 5xx/네트워크(일시)→re-throw. 예) booking `PaymentConfirmedEventListener`의 `seatRestClient.confirmSold()`.
- **2계층 분류(상보적):** 리스너 레벨(`isPermanent`)은 **ack vs 전파**를 결정하고, 컨테이너 레벨(`KafkaConfig`의 `addNotRetryableExceptions`)은 전파된 예외의 **재시도 횟수 vs 즉시 DLT**를 결정한다.
- **동명 `DeserializationException` 2종 구분:** ① envelope(외부) 역직렬화 = spring-kafka `org.springframework.kafka.support.serializer.DeserializationException` → 컨테이너 not-retryable로 **즉시 DLT**(리스너 실행 안 됨). ② payload(내부) 역직렬화 = `com.ticketrush.global.json.DeserializationException`(`BusinessException` 하위) → 리스너 catch에서 **영구→ack**. 이름만 같고 경로가 다르다.
- **groupId/topic 상수화:** topic은 각 이벤트의 `*Event.TOPIC` 상수를, groupId는 `com.ticketrush.global.event.KafkaConsumerGroup`의 상수를 참조한다(리터럴 하드코딩 금지). `@KafkaListener`는 컴파일 타임 상수 String을 요구하므로 상수는 `public static final String`으로 둔다(enum 불가).
- **파티션 블로킹:** 일시 실패 재시도(5회, 1→2→4→8→16s ≈ 31초)는 `MAX_POLL_INTERVAL_MS`(300초)보다 훨씬 짧아 리밸런싱을 유발하지 않는다. 다만 재시도 동안 컨슈머 스레드가 점유되고 같은 배치의 후속 레코드가 대기하므로, 현 트래픽 수준에서만 허용된다.

---

## 3. 사용 가이드 (How to Use)

새로운 비즈니스 로직에서 카프카로 이벤트를 발행하려면 다음 **2가지 단계**만 거치면 됩니다. 카프카 인프라 기술에 종속되지 않고 비즈니스 로직에만 집중할 수 있습니다.

### Step 1. 커스텀 이벤트 클래스 정의하기
`DomainEvent` 인터페이스를 구현하는 레코드(또는 클래스)를 생성합니다. 이곳에 어떤 토픽으로 보낼지, 키는 무엇인지 정의합니다.

예시 코드

```java
package com.ticketrush.domain.order.event;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.EventUtils;

// 전송할 실제 데이터들 (Payload에 들어갈 내용)
public record OrderCompletedEvent(
    String orderId,
    Long userId,
    int totalAmount
) implements DomainEvent {

    // 토픽명은 리터럴을 하드코딩하지 말고 상수로 노출해 컨슈머가 재사용한다(#269).
    public static final String TOPIC = "order-events";

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public String key() {
        // 파티션 분배를 위한 Key (여기서는 동일한 유저의 주문은 순서를 보장하기 위해 userId 사용)
        return String.valueOf(userId); 
    }

    @Override
    public String eventName() {
        // 이벤트 종류 명시
        return "OrderCompleted"; 
    }

    @Override
    public String traceId() {
        // 로깅 및 분산 추적을 위한 Trace ID 자동 추출
        return EventUtils.extractTraceId(); 
    }
}
```

### Step 2. 비즈니스 로직에서 `EventPublisher` 호출하기
`KafkaTemplate`을 직접 조작하지 마세요. `EventPublisher` 인터페이스를 주입받아 직관적으로 발행(`publish`)합니다.

```java
package com.ticketrush.domain.order.service;

import com.ticketrush.domain.order.event.OrderCompletedEvent;
import com.ticketrush.global.eventpublisher.EventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService {

    // 설정(app.event-publisher.type)에 따라 KafkaDomainEventPublisher 또는 OutboxEventPublisher가 주입됩니다(§4).
    private final EventPublisher eventPublisher;

    @Transactional
    public void completeOrder(String orderId, Long userId, int amount) {
        // 1. 비즈니스 로직 실행
        // ... 주문 완료 처리 로직 ...

        // 2. 이벤트 객체 생성
        OrderCompletedEvent event = new OrderCompletedEvent(orderId, userId, amount);

        // 3. 카프카로 이벤트 발행 (내부적으로 JSON 직렬화 및 Envelope 포장이 일어남)
        eventPublisher.publish(event);
    }
}
```

### (참고) 발행된 이벤트를 수신하는 방법 (Consumer)
발행된 데이터는 `DomainEventEnvelope` 형태로 수신됩니다. 수신 후 `payload` 필드를 역직렬화하여 사용합니다. 에러 처리는 **§2 컨슈머 에러 처리 표준**을 따릅니다(topic/groupId 상수, 수동 ack, 영구→ack / 일시→re-throw).

```java
import com.ticketrush.domain.order.event.OrderCompletedEvent;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerErrorPolicy;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.json.JsonConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

  // Publisher에서 직렬화할 때 썼던 동일한 JsonConverter를 주입받습니다.
  private final JsonConverter jsonConverter;

  // topic/groupId는 리터럴이 아니라 상수를 참조합니다(§2.3).
  @KafkaListener(topics = OrderCompletedEvent.TOPIC, groupId = KafkaConsumerGroup.TICKET)
  public void handleOrderEvent(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {
    try {
      // String 형태의 payload를 실제 객체로 역직렬화 후 비즈니스 로직 처리
      OrderCompletedEvent event =
          jsonConverter.deserialize(envelope.payload(), OrderCompletedEvent.class);

      // ... 유저 알림 발송, 통계 적재 등 필요한 로직 수행 ...

      ack.acknowledge(); // 성공 시에만 커밋
    } catch (Exception e) {
      // §2 표준: 영구(비즈니스/결정적) 실패는 로그 후 ack, 일시(인프라) 실패는 re-throw 하여 재시도→DLT로 보존.
      if (KafkaConsumerErrorPolicy.isPermanent(e)) {
        if (KafkaConsumerErrorPolicy.isExpectedConflict(e)) {
          log.warn("주문 이벤트 처리 중 예상된 상태충돌(멱등 처리). eventId: {}", envelope.eventId(), e);
        } else {
          log.error("[CRITICAL] 주문 이벤트 처리 실패! 확인이 필요합니다. eventId: {}", envelope.eventId(), e);
        }
        ack.acknowledge();
      } else {
        log.warn("주문 이벤트 처리 중 일시적 오류. 재시도합니다. eventId: {}", envelope.eventId(), e);
        throw e;
      }
    }
  }
}
```

> `eventPublisher.publish()`의 **실제 발행 방식은 설정(`app.event-publisher.type`)에 따라 두 모드로 갈린다** — **§4**. 그리고 재전달(at-least-once)에 대비한 **중복 수신 방지**는 **§5(Inbox)** 를 따른다.

---

## 4. 발행 전략: Outbox vs AFTER_COMMIT

§3의 `eventPublisher.publish(event)`는 **동일한 한 줄**이지만, 실제 발행 방식은 `app.event-publisher.type` 설정으로 두 모드 중 하나가 선택된다. 발행 코드는 그대로 두고 배선만 바꾼다.

### 4.1. 두 모드 비교

| 구분 | `outbox` | `kafka` (AFTER_COMMIT) |
|------|----------|------------------------|
| 설정값 | `app.event-publisher.type: outbox` | `app.event-publisher.type: kafka` |
| 구현체 | `OutboxEventPublisher` | `KafkaDomainEventPublisher` |
| 발행 시점 | 호출부 트랜잭션에 참여해 `outbox` 테이블에 INSERT → 폴링 relay가 나중에 Kafka로 send | 트랜잭션이 있으면 커밋 직후(AFTER_COMMIT) send, 없으면 즉시 send |
| 원자성 | **비즈니스 변경 + 발행 기록이 한 커밋**(트랜잭셔널 아웃박스) | 커밋 후 send이므로 "커밋됐으나 send 실패"의 유실 창이 존재 |
| 전달 보장 | at-least-once(relay가 `PENDING`·`FAILED`를 재시도) | fire-and-forget(비동기 send, 실패 시 error 로그만) |
| 선택 기준 | 발행 유실이 정합성 사고로 이어지는 흐름(Saga 보상 등) | 유실 허용 가능한 알림·통계성 발행 |

두 구현체는 `@ConditionalOnExpression`으로 상호배타 활성화된다(`'${app.event-publisher.type}' == 'outbox'` / `== 'kafka'`). 서비스는 `EventPublisher` 인터페이스만 주입받으므로(§3 Step 2) 어느 모드든 발행 코드는 동일하다.

> ⚠️ `OutboxEventPublisher.publish()`는 **활성 트랜잭션이 필수**다. 트랜잭션 밖에서 호출하면 `IllegalStateException`을 던진다(*"…비즈니스 변경과 Outbox 저장의 원자성을 보장하려면 호출부를 @Transactional 등으로 감싸세요."*). outbox 모드 서비스는 발행부를 반드시 `@Transactional` 안에서 호출한다.

**현재 배선:** outbox 모드는 **seat-service**만 사용한다. 나머지 서비스(booking·user·performance·payment·ticket·auth)는 `kafka` 모드다. booking-service는 outbox 인프라(relay·retention 스케줄러·`app.outbox` 설정)를 갖추고 있으나 프로파일이 `kafka`라 휴면 상태다.

### 4.2. Outbox 모드 배선

outbox 모드를 켜면(`app.event-publisher.type: outbox`) 두 스케줄러가 함께 동작한다(둘 다 `@ConditionalOnExpression`로 outbox일 때만 등록).

| 스케줄러 | 주기 | ShedLock 이름 | 역할 |
|----------|------|---------------|------|
| `OutboxRelayScheduler` | `fixedDelay=5s` | `outboxRelay-{service}` | `PENDING`·`FAILED` row를 배치로 Kafka에 send |
| `OutboxRetentionScheduler` | `fixedDelay=1h` | `outboxRetention-{service}` | 보존기간 지난 `SENT` row 삭제 |

ShedLock 이름은 서비스별로 유일해야 한다(예: `outboxRelay-seat`, `outboxRelay-booking`) — 다중 인스턴스 중복 실행 방지.

**`app.outbox.*` 설정 키**

| 키 | 기본값 | 의미 |
|----|--------|------|
| `aggregate-types` | `[]` (빈 값이면 relay가 아무것도 안 함) | 이 서비스가 relay·정리할 애그리거트 타입 목록(예: `[Seat]`) |
| `batch-size` | `100` | relay 1회 배치 크기 |
| `max-retries` | `3` | 발행 재시도 상한. 초과 시 `DEAD` 전이 |
| `retention-hours` | `72` | `SENT` row 보존 시간 |

> **여러 서비스가 하나의 `outbox` 테이블을 공유**한다. 각 서비스는 `aggregate-types`로 **자기 소유 row만** relay·정리한다. `aggregateType`은 이벤트 패키지 `com.ticketrush.shared.<aggregate>.event`에서 유도되어 첫 글자가 대문자화된다(`booking` → `Booking`). 따라서 `aggregate-types` 값이 발행 이벤트의 aggregateType과 일치해야 한다.

**상태 흐름 (`OutboxStatus`)**

`PENDING`(발행 대기) → relay send 성공 시 `SENT`(발행 완료) / 실패 시 `FAILED`(발행 실패, `retryCount++`) → 재시도가 `max-retries`에 도달하면 `DEAD`(최종 실패). relay는 `PENDING`·`FAILED`만 대상으로 재시도하고, `SENT`·`DEAD`는 종결 상태다.

> `DEAD` 전이 시 **Slack 알림**이 발송된다(제목 `[Outbox DEAD] 이벤트 발행 재시도 상한 초과`). 알림 본문에는 PII 방지를 위해 원문 에러를 넣지 않고 `eventId`만 담아 outbox 테이블 조회를 유도한다. 상세 원인은 `[CRITICAL]` 로그에 남는다.

### 4.3. 발행 코드는 두 모드가 동일

§3 Step 2의 `eventPublisher.publish(event)`가 그대로다. 모드에 따라 달라지는 것은 배선(설정·스케줄러)뿐이다. outbox 모드에서는 위 콜아웃대로 **호출부를 `@Transactional`로 감싸는 것**만 지키면 된다.

---

## 5. 중복 수신 방지: Inbox 패턴 (#110)

Kafka는 at-least-once라 같은 이벤트가 재전달될 수 있다. **비멱등 소비**(상태 전이·좌석 선점·보상 처리 등)는 재처리되면 사고가 나므로 Inbox로 감싸 한 번만 처리한다.

### 5.1. 언제 쓰나

- **비즈니스 컨슈머는 기본적으로 Inbox로 감싼다.** 현재 모든 도메인 `@KafkaListener`가 `InboxService.runIfFirst`를 거친다(§5.5 목록). Inbox를 쓰지 않는 유일한 `@KafkaListener`는 DLT 모니터(`DeadLetterConsumer`, groupId `dlt-monitor-group`)로, 이는 멱등 처리 대상이 아니라 실패 메시지 적재용이다.
- **도메인 멱등은 2차 방어(방어 심층화)로 유지한다.** 예: `TicketIssueUseCase`의 `alreadyIssued`, 좌석 SOLD 확정의 409 응답. Inbox가 1차 중복 차단을, 도메인 멱등이 2차 안전망을 담당한다.

### 5.2. 사용법

리스너에는 트랜잭션이 없고 비즈니스 트랜잭션은 usecase에서 시작한다. `InboxService.runIfFirst`가 `@Transactional`(REQUIRED)로 트랜잭션을 열고, business 콜백을 **같은 트랜잭션에 조인**시켜 "처리 + Inbox 기록"을 원자적으로 커밋한다.

```java
boolean processed =
    inboxService.runIfFirst(
        KafkaConsumerGroup.SEAT, envelope, () -> seatFacade.releaseBookedSeat(seatId, bookingNumber));

if (processed) {
  // 최초 수신 → 이번에 처리함
} else {
  // 이미 처리된 이벤트 → 스킵(중복 수신)
}
```

- `runIfFirst`는 최초 수신이면 business를 실행하고 Inbox에 기록한 뒤 `true`, 이미 처리한 이벤트면 아무것도 하지 않고 `false`를 반환한다.
- business가 던진 예외는 잡지 않고 전파한다 → 트랜잭션 롤백(Inbox 미기록) → 재전달 시 재처리.

### 5.3. §2 에러 표준과의 결합

`runIfFirst`를 §2.2 표준 템플릿 위에 얹는다. 결과별 대응:

| 상황 | 처리 |
|------|------|
| 정상 처리(`processed=true`) 또는 스킵(`false`) | `ack` |
| `DuplicateEventException`(동시 중복 수신으로 inbox unique 경합) | **`ack`**(멱등 정상) — 전용 catch 블록 |
| business의 비즈니스 예외(영구) | §2 표준대로 `[CRITICAL]`/`warn` 후 `ack` |
| business의 일시(인프라) 예외 | 전파(re-throw) → 롤백(Inbox 미기록) → §2의 재시도→DLT. 재전달 시 재처리 |

```java
try {
  boolean processed =
      inboxService.runIfFirst(KafkaConsumerGroup.XXX, envelope, () -> usecase.execute(...));
  // processed 여부 로깅
  ack.acknowledge();
} catch (DuplicateEventException e) {
  ack.acknowledge();                        // 동시 중복 → 멱등 정상
} catch (Exception e) {
  // §2.2 표준 분기(영구 → ack / 일시 → re-throw)
}
```

### 5.4. 멱등 키

Inbox 중복 판정은 복합 unique **`uk_inbox_group_event (consumer_group, event_id)`** 로 한다(`inbox` 테이블). **컨슈머 그룹별로** 키가 갈리므로, 같은 이벤트라도 여러 컨슈머 그룹(예: `seat-group`·`booking-group`·`ticket-group`)이 **각자 한 번씩** 소비한다. `created_at`에는 retention용 인덱스 `idx_inbox_created_at`가 있다.

### 5.5. 적용 리스너 목록

`inboxService.runIfFirst`를 사용하는 리스너(10개):

| 서비스 | 리스너 | groupId | 감싸는 처리 |
|--------|--------|---------|-------------|
| seat | `BookingCreatedEventListener` | `seat-group` | `seatFacade.tryLockSeat(...)` |
| seat | `BookingCanceledEventListener` | `seat-group` | `seatFacade.releaseBookedSeat(...)` |
| seat | `PaymentCanceledEventListener` | `seat-group` | `seatReleaseSoldSeatUseCase.execute(...)` |
| seat | `PerformanceCreatedEventListener` | `seat-group` | `seatFacade.createDefaultSeats(...)` |
| booking | `PaymentConfirmedEventListener` | `booking-group` | `bookingConfirmUseCase.execute(...)` (확정만; SOLD HTTP는 Inbox 밖) |
| booking | `PaymentCanceledEventListener` | `booking-group` | `bookingMarkRefundedUseCase.execute(...)` |
| booking | `SeatHoldFailedEventListener` | `booking-group` | `bookingCancelUseCase.execute(...)` |
| ticket | `PaymentConfirmedEventListener` | `ticket-group` | `ticketIssueUseCase.execute(...)` |
| ticket | `PaymentCanceledEventListener` | `ticket-group` | `ticketCancelUseCase.execute(...)` |
| payment | `BookingExpiredEventListener` | `payment-group` | `paymentFacade.registerExpiredBooking(...)` |

> booking `PaymentConfirmedEventListener`는 **확정(DB 상태 전이)만 Inbox로 감싸고**, 커밋 후 좌석 SOLD 확정 HTTP 호출은 Inbox 밖에서 수행한다(재소비 시에도 `bookingNumber`를 재조회해 SOLD를 멱등하게 재호출). 크로스서비스 호출은 트랜잭셔널 원자 단위에 포함할 수 없기 때문이다.

### 5.6. Inbox retention (#314)

`inbox` 테이블은 방치하면 무한 증가하므로, 보존기간이 지난 row를 **booking-service 단독**에서 정리한다(전역 공유 테이블이라 한 서비스가 대표로 정리). `app.inbox.retention.enabled=true`일 때만 동작하며 기본 비활성이다.

| 키 | 기본값 | 의미 |
|----|--------|------|
| `enabled` | `false` | 운영에서 단일 서비스(booking)만 `true` |
| `retention-days` | `30` | 보존기간 |
| `min-retention-days` | `7` | 보존 하한 = Kafka replay 윈도우. `retention-days`가 이보다 짧으면 중복 재처리 위험이 있어 purge를 건너뛴다(fail-safe) |
| `batch-size` | `1000` | 청크 삭제 배치 크기(배치마다 독립 트랜잭션 → 대용량 단일 삭제 회피) |
| `max-batches-per-run` | `100` | 1회 실행당 배치 수 상한 |

`InboxRetentionScheduler`(booking, `@Scheduled` 1h + `@SchedulerLock inboxRetention-booking`)가 청크 단위로 삭제한다.