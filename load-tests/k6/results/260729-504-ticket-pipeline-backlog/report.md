# #504 티켓 발급 파이프라인 backlog 회복시간 — 팬아웃의 회복시간은 느린 쪽이 정한다

- **측정일** 2026-07-29, UTC
- **대상** `https://api.ticketrush.store` — EC2 단일 인스턴스 배포본(`m7i-flex.large`, 2 vCPU), `IMAGE_TAG=1f2ed5d2…`
- **앱 코드 변경** 0줄. 이 회차는 배포가 아니라 실행이다(#512 규약: `test` 라벨은 배포 묶음에 넣지 않는다)
- **런북** `docs/load-test-guide.md` §15 · **수치 SSOT** `metadata.txt`

---

## 1. 요약

| | 확정된 것 |
|---|---|
| **이 파이프라인은 이번이 첫 측정이다** | 측정 직전 `payment-confirmed-topic` 의 LOG-END-OFFSET 이 파티션 3개 모두 **0** 이었다. #348 이 "lag 전 구간 0" 으로 남긴 것은 파이프라인이 한가했다는 뜻이 아니라 **한 건도 흐른 적이 없다**는 뜻이었다 |
| **주입 경로가 성립한다** | 결제 API 를 타지 않고 `payment-confirmed-topic` 에 직접 넣어 `booking CONFIRMED → seat SOLD → ticket 발급` 전 구간이 실제로 돈다. DLT 0건 |
| **회복시간 = 약 8분** | 20,000건 스파이크 → 정점 19,870 → lag 0 까지 **479초**. 하강이 끝까지 선형이라 꼬리가 끌리는 구간이 없다 |
| **팬아웃 두 그룹의 드레인율이 2.05배 갈린다** | `ticket-group` **85.9/s**(DB INSERT 한 번) vs `booking-group` **42.0/s**(seat-service 동기 HTTP 왕복이 낀다). 같은 토픽·같은 파티션 수·같은 concurrency 인데 그렇다. **회복시간은 느린 쪽이 정한다** — 티켓만 보면 4분이지만 그때 좌석 SOLD 확정은 아직 절반이 남아 있다 |
| **`concurrency=1` 이 처음으로 노출됐다** | #348 은 릴레이(실효 53.2/s)가 앞에서 조여 컨슈머까지 부하가 닿지 않았다(lag 최대 52). 릴레이를 우회하니 적체가 **2만 건** 쌓인다 |
| **병목은 어느 풀도 아니다** | 적체 2만 건이 8분간 남아 있는 동안 seat-service 톰캣 스레드는 **최대 1개**가 일했고 HikariCP 대기는 **0**이었다. 용량이 아니라 **직렬화**가 상한이다 |
| **그래도 `concurrency=3` 은 3배를 주지 않는다** | 회복 구간 CPU 가 이미 **80.84%** 다. 남은 여유로 계산한 상한은 약 **1.4배** — 2 vCPU 를 늘리지 않는 한 이슈 본문의 "3배 확보" 는 성립하지 않는다 |
| **유실·중복 0** | 주입 23,000건에 대해 `confirmed = sold = tickets = inbox(양쪽) = 23,000`. `duplicate`·`already_issued` 카운터는 시계열조차 생기지 않았다 |
| **워밍업이 드레인율을 1.7배 흔든다** | 기동 직후 1차 스모크 21/s → 3차 35/s. 워밍업 전 회차를 본 회차로 쓰면 회복 곡선 앞부분이 통째로 거짓이 된다 |

---

## 2. 이슈 서사와 실측의 차이

**(a) "포화점을 알아야 주입률을 정할 수 있다" 는 전제는 #348 로 채울 수 없었다.**

이슈 코멘트는 blocked-by 이유로 "현재 구성의 포화점을 알아야 이 회차의 주입률을 정할 수 있다" 를 들었다. 그런데 #348 은 포화점을 내지 못했다 — 조회 축을 올리면 집 회선이 먼저 차고, 회선을 피해 낮추면 앱이 여유로웠다. 인용할 수 있는 숫자는 릴레이 실효 상한(#489, 53.2/s)뿐인데 **이 회차는 릴레이를 우회하므로 그 값이 상한이 아니다.**

대신 **드레인율을 이 회차 안에서 직접 만들었다.** 소량 무페이싱 스모크로 하강 기울기를 재고(§5), 그 값으로 baseline 과 스파이크를 사이징했다. 별도 캘리브레이션 회차는 필요 없었다 — 회복 구간은 유입이 정확히 0이라 하강 기울기가 그대로 드레인율이다.

**(b) 좌석을 공유할 수 없다 — #402 와 전제가 다르다.**

`seed_entry.sql`(#402)은 LOADTEST 첫 공연의 MIN seat_id 하나를 25,000 booking 전체가 공유한다. 검표 경로에 seat-service 가 아예 없어서 가능했던 선택이다.

이 회차의 `booking-group` 은 `booking.confirm()` 뒤 seat-service `POST /api/v1/internal/seat/sold` 를 부르고, `SeatConfirmSoldUseCase` 는 `confirmSoldById(seatId, bookingNumber, HOLD, SOLD)` 로 **좌석이 HOLD 이고 booking_number 가 일치할 때만** 갱신한다. 좌석을 공유하면 payload 의 `seat_id` 를 하나로 고정해야 하는데, 그러면 `BookingConfirmUseCase` 가 `BOOKING_SEAT_MISMATCH` 로 **확정 단계에서** 전건을 죽인다. 그래서 **좌석 : 예매 = 1:1** 로 심었다.

**(c) 이슈가 말하지 않은 시한폭탄이 둘 있었다.**

| 스케줄러 | 무엇을 하나 | 코호트에 미치는 영향 |
|---|---|---|
| `BookingExpireUseCase` | `PENDING` 이고 `created_at <= now-5분` 인 예매를 EXPIRED 로. `PAYMENT_WAIT_MINUTES=5`, 60초 주기, 배치 100 × 최대 200회 = **tick 당 20,000건** | 시드가 `created_at` 을 현재 시각으로 박으면 **시딩 5~6분 뒤 코호트 30,000건이 통째로 EXPIRED** 가 된다. 회차의 "baseline 5분" 이 정확히 도화선 길이다 |
| `SeatStatusScheduler` | 만료된 HOLD 를 60초 주기로 AVAILABLE 로 해제 | 해제되면 `confirmSoldById` 가 0행을 갱신해 전건 실패 |

둘 다 시각 비교라 시드가 `created_at`·`hold_expired_at` 을 **6시간 미래로** 밀어 막았다. 검증 SELECT 의 `expiry_safe=30000` 이 그 확인이다. **이 두 개를 모르고 돌렸으면 회차 자체가 성립하지 않았다** — 그리고 증상은 "티켓이 안 나온다" 라서 주입 형식을 의심하는 쪽으로 시간을 썼을 것이다.

**(d) 유선 형태는 한 메시지 안에서 직렬화 규칙이 두 개다.**

Kafka value 는 `PaymentConfirmedEvent` 가 아니라 `DomainEventEnvelope` 이고, 이벤트 본문은 봉투 안 `payload` 에 **JSON 문자열로 escape 되어** 들어간다.

| 층 | 매퍼 | 규칙 |
|---|---|---|
| 봉투 | `JacksonMapperUtils.enhancedJsonMapper()` (spring-kafka 전용) | camelCase |
| payload | 앱 `ObjectMapper`(`JacksonConfig`) | **snake_case** + `yyyy-MM-dd HH:mm:ss` |

그리고 헤더 `__TypeId__: com.ticketrush.global.event.DomainEventEnvelope` 가 필수다. `USE_TYPE_INFO_HEADERS=true` 인데 `spring.json.value.default.type` 이 없어서(`KafkaConfig.java:119`), 헤더가 빠지면 `DeserializationException` 이 되고 그 예외는 `addNotRetryableExceptions` 에 걸려 **재시도 없이 즉시 DLT** 로 간다.

---

## 3. 파이프라인 구성 (완료조건 5)

코드가 아니라 **배포본에서 읽은 값**이다.

```
Topic: payment-confirmed-topic   PartitionCount: 3   ReplicationFactor: 1

ticket-group   payment-confirmed-topic 0/1/2  →  consumer-ticket-group-2   (단일 CONSUMER-ID)
booking-group  payment-confirmed-topic 0/1/2  →  consumer-booking-group-5  (단일 CONSUMER-ID)
```

| 항목 | 값 | 근거 |
|---|---|---|
| 파티션 수 | **3** | 브로커 auto-create (`KAFKA_NUM_PARTITIONS: 3`). `payment-confirmed-topic` 에 대한 `NewTopic` 빈은 없다 |
| 소비 병렬도 | **1** (그룹당) | 파티션 3개가 CONSUMER-ID 하나에 전부 배정된다. `kafkaListenerContainerFactory` 에 `setConcurrency` 호출이 없고 `@KafkaListener` 에도 `concurrency` 속성이 없다 |
| `max.poll.records` | 20 | `KafkaConfig` 상수 — 실효 드레인율의 상한 요인 |
| `fetch.max.wait.ms` | 500 | 〃 |
| DLT 백오프 | `ExponentialBackOffWithMaxRetries(5)`, 1s ×2, max 60s (합 약 31초) | 일시 실패 1건마다 그 파티션이 31초 멈춘다 |

**측정 직전 이 토픽의 LOG-END-OFFSET 은 파티션 3개 모두 0이었고, `inbox` 에 `PaymentConfirmed` 행이 0건, DLT 토픽이 아예 없었다.** 결제 API 를 탈 수 없었으니 당연한 결과지만, #348 리포트의 "lag 전 구간 0" 이 **"한가했다" 가 아니라 "한 건도 흐른 적 없다"** 였다는 것을 확정한다.

---

## 4. 시딩 (완료조건 1)

`load-test/seed/seed_payment_pipeline.sql`, `@mode='seed' @count=30000`. 1.4초.

| 축 | 값 | 기존 코호트와의 분리 |
|---|---|---|
| 공연 title | `LTP-A` (perf_id 19) | `LOADTEST-%`(#348·#344·#345) 에도 `LTC-%`(#403) 에도 안 걸린다 |
| booking_number | `LT-P000000001` ~ | `LT-E`(#402), `LT-X`(#345) 와 프리픽스가 다르다 |
| booking_id | `2000001 ~ 2030000` | #402 의 `1000001~1019399` 와 겹치지 않는다. 측정 시점 AUTO_INCREMENT 는 1,095,192 였다 |
| seat_id | `76054 ~ 106053` | 신규 30,000석 |

검증 SELECT 는 전부 통과했다. **하나라도 어긋나면 측정을 진행하지 않는다** — 주입 스크립트가 `seat_id = SEAT_ID_MIN + idx` 로 값을 만들기 때문에 좌석 연속성이 깨지면 전건이 `BOOKING_SEAT_MISMATCH` 로 죽는다.

```
contiguous_seats=1  seat_number_ordered=1  contiguous_bookings=1  seat_booking_aligned=1
pending=30000       held=30000             expiry_safe=30000
```

> `seat_booking_aligned` 은 booking i 의 `seat_id` 가 정확히 좌석 `S-i` 를 가리키는지를 본다. `expiry_safe` 는 `created_at` 이 전부 미래인지 — §2(c) 의 만료 스케줄러 방어다.

계정은 새로 만들지 않고 `seed_load.sql` 의 `loadtest@ticketrush.local`(user_id 4)을 재사용했다. 인터넷에 열린 배포본에 계정을 하나라도 덜 만든다.

---

## 5. 프리플라이트와 스모크

### 5.1 1건 프리플라이트 — lag 0 은 처리됐다는 증거가 아니다

역직렬화에 실패해 DLT 로 가도 lag 은 0이 된다. 그래서 1건만 넣고 **DB 네 곳을 직접** 봤다.

| 확인 | 결과 |
|---|---|
| `inbox` | `booking-group` 1행, `ticket-group` 1행 (`event_type='PaymentConfirmed'`) |
| `ticket` | `ticket_id=52001, booking_id=2000001, user_id=4, UNUSED` |
| `booking` | `CONFIRMED`, `confirmed_at=2026-07-29 07:08:28` |
| `seat` | `SOLD`, `booking_number=LT-P000000001` |
| DLT 토픽 | 없음 |

`ticket.user_id` 가 4로 들어간 것에 주의한다 — 티켓 소유자는 booking 이 아니라 **이벤트 payload 의 `user_id`** 를 복제한다.

### 5.2 스모크 3회 — 워밍업이 드레인율을 40% 흔든다

각 2,000건 무페이싱. **수치는 폐기하고 워밍업 확인에만 쓴다.**

| 회차 | 주입 종료 → lag 0 | booking-group | ticket-group |
|---|---|---|---|
| 1차 | 90초 | 약 21/s | 약 39/s |
| 2차 | 61초 | 약 31/s | 약 55/s |
| **3차** | **57초** | **약 35/s** | **약 65/s** |

1차와 3차가 **1.7배** 차이난다. 인스턴스 기동 직후라 JIT 워밍업이 끝나지 않은 것이다. 61초 → 57초는 잡음 범위로 보고 고원 도달로 판정했다.

> 이 회차가 아니었으면 놓쳤을 함정이다. 기동 직후 한 번만 돌려 "드레인율 21/s" 로 적었으면 실제의 60% 를 리포트에 남겼을 것이다. 런북 §15.3-5 에 옮겼다.

스모크는 `ticketrush_ticket_issue_total` 시계열을 낳는 역할도 한다 — Micrometer 지연 등록이라 첫 발급 전에는 시계열이 아예 없다.

---

## 6. 회차 1 — baseline: 흡수된다

드레인율(약 35/s) 훨씬 아래인 10/s 로 5분간 흘렸다.

| 항목 | 값 |
|---|---|
| 주입 건수 | 3,000 (offset 0~2999) |
| 명목 / **실측** 주입률 | 10/s / **9/s** (3,000건 / 302초) |
| 구간 | `2026-07-29T07:16:05Z` ~ `07:21:07Z` |
| 주입 종료 시점 lag | **0** (종료 3초 뒤 첫 표본이 이미 0) |

**적체가 생기지 않는다.** 유입이 드레인율 아래면 파이프라인은 사실상 즉시 처리한다. 이 구간이 있어야 뒤의 회복 곡선이 "원래 못 따라가는 구성" 이 아니라 "스파이크 때문에 밀린 것" 임을 말할 수 있다.

> 실측 주입률이 명목보다 낮은 것(10 → 9/s)은 페이서가 매 초 `sleep 1` + 줄 생성 시간만큼 느린 쪽으로만 드리프트하기 때문이다. 그래서 리포트는 명목값을 쓰지 않는다.

---

## 7. 회차 1 — 스파이크와 회복 (완료조건 3)

20,000건(offset 3000~22999)을 **페이싱 없이** 밀어 넣었다. 3초에 다 들어갔다(6,666/s).

### 7.1 회복시간

| | booking-group | ticket-group |
|---|---|---|
| 정점 적체 | **19,870** | **19,505** |
| 정점 시각 | 주입 종료 +6초 | 주입 종료 +6초 |
| 정점 → 30 이하 | 473초 | 227초 |
| 정점 → 0 | **473초** | **227초** |
| **주입 종료 → 0** | **479초 (7분 59초)** | **233초 (3분 53초)** |
| 평균 드레인율 | **42.0/s** | **85.9/s** |

**backlog 회복시간 = 약 475~479초(약 8분).** 스크립트의 `[drain] drained` 줄은 `elapsed_from_inject_end=475s` 로 찍혔고, 마지막 표본의 UTC 시각으로 계산하면 479초다. 차이는 폴링 간격(5초)과 `kafka-consumer-groups --describe` 호출 지연이며, **회복시간의 해상도가 그만큼**이다. 직전 표본(`07:29:10Z`)에서 199건이 남아 있었으므로 실제 0 도달은 그 사이다.

"정점 → 30 이하" 와 "정점 → 0" 이 같은 값인 것은 하강이 끝까지 선형이라 마지막 표본 구간에서 199 → 0 으로 한 번에 떨어졌기 때문이다. **꼬리가 길게 끌리는 구간이 없다** — #348 의 outbox backlog 가 정점 +86초에 2차 파동을 만든 것과 대조된다.

### 7.2 회복시간은 느린 쪽이 정한다

두 그룹은 **같은 토픽, 같은 파티션 수(3), 같은 concurrency(1)** 이고 같은 순간에 같은 20,000건을 받았다. 그런데 드레인율이 **2.05배** 갈린다. 차이는 처리 내용뿐이다.

```
ticket-group   : inbox tx → ticket INSERT                                     85.9/s
booking-group  : inbox tx → booking UPDATE → bookingNumber 재조회
                 → seat-service HTTP 왕복 → seat UPDATE+SELECT
                 → Redis forceRelease 1왕복 → SSE submit                      42.0/s
```

팬아웃은 두 그룹이 **독립적으로** 같은 토픽을 소비하므로, 파이프라인 전체가 "회복됐다" 고 말할 수 있는 시점은 느린 쪽인 booking-group 기준 **8분**이다. 티켓만 보면 4분이지만, 그 시점에 좌석 SOLD 확정은 아직 절반이 남아 있다.

### 7.3 두 컨슈머가 같은 2 vCPU 를 두고 경합한다

`ticket-group` 이 0에 도달한 `07:25:12Z` 를 경계로 booking-group 의 처리율이 뛴다.

| 구간 | booking-group 처리율 | 호스트 CPU |
|---|---|---|
| 두 그룹 동시 (`07:21:30Z ~ 07:25:10Z`) | avg **36.2/s** / max 39.6/s | 81.13% |
| booking 단독 (`07:25:30Z ~ 07:29:15Z`) | avg **44.8/s** / max 45.6/s | 80.25% |

**같은 CPU 점유율에서 처리율만 24% 오른다.** 팬아웃 소비자를 늘리면 서로의 처리율을 깎는다는 직접 증거다.

### 7.4 병목은 어느 풀도 아니다

| 축 | 회복 구간 값 | 읽는 법 |
|---|---|---|
| 호스트 CPU | avg **80.84%** / max 87.51% | baseline 26.11%, 드레인 후 유휴 28.19% → 드레인이 CPU 를 **약 52%p** 더 쓴다 |
| HikariCP pending | **0** (전 인스턴스) | DB 커넥션 대기 없음 |
| tomcat busy (seat-service) | avg 0.34 / **max 1** | 내부 API 는 한 번에 한 요청만 받는다 — 컨슈머가 1스레드라 그 이상이 올 수 없다 |
| tomcat busy (booking/ticket) | 0 | HTTP 유입이 아예 없다 |
| JVM heap peak | booking 94 / ticket 91 / seat 82 MiB | 메모리는 문제 밖 |

**스레드 풀도 DB 풀도 포화하지 않았다.** 적체 2만 건이 8분간 남아 있는 동안 seat-service 의 톰캣 스레드는 최대 **1개**가 일했다. 병목은 용량이 아니라 **직렬화**다 — `concurrency=1` 이 파티션 3개를 한 스레드로 훑는다.

> **그렇다고 `concurrency=3` 이 3배를 주지는 않는다.** 회복 구간 CPU 가 이미 80.84% 이고 유휴가 28.19% 다. 드레인에 귀속되는 몫이 약 52%p 이므로, CPU 를 100% 까지 쓴다고 가정하면 상한은 `42.0 × (52+19)/52 ≈ 57/s` — **약 1.4배**다. 2 vCPU 를 늘리지 않는 한 3배는 나오지 않는다. (이슈 본문의 "3배 확보" 는 파티션 수만 본 값이다.)

### 7.5 파티션 스큐는 없다 — 단, 그 판정을 Prometheus 로 하면 안 된다

**브로커의 파티션별 LOG-END-OFFSET** 이 권위 있는 근거다.

| 파티션 | 0 | 1 | 2 | 합 |
|---|---|---|---|---|
| LOG-END-OFFSET | 9,559 | 9,740 | 9,702 | 29,001 |

평균 9,667 대비 편차가 **1.1%** 다. 세 파티션에 사실상 완전히 균등하게 들어갔다 — 메시지 key 를 `bookingId` 로 두어 코호트가 흩어졌기 때문이다. 따라서 `concurrency` 를 3으로 올리면 파티션 배분 자체는 걸림돌이 아니다.

### 7.6 ⚠️ `kafka_consumer_fetch_manager_records_lag` 는 이 회차의 backlog 곡선으로 쓸 수 없다

이 회차에서 **처음 확인한 함정**이다. Prometheus 로 뜬 적체는 단조 감소하지 않고 튄다:

```
booking-service:8090   07:22:15Z 4,632 → 07:22:30Z 4,052 → 07:22:45Z 10,013 → 07:23:00Z 9,413
ticket-service:8090    07:21:45Z 4,692 → 07:22:00Z 9,973 → 07:22:15Z 8,653 → 07:22:30Z 14,031
```

같은 구간을 브로커에서 5초로 뜬 값(`lag-samples-spike.csv`)은 **완벽하게 단조 감소**한다. 원인은 지표의 정의다 — 이건 파티션별로 **"마지막 fetch 응답 시점의 lag"** 이고, `max.poll.records=20` 인 단일 스레드가 파티션 3개를 번갈아 훑으므로 파티션마다 갱신 시각이 다르다. `sum by (instance)` 는 **신선한 값과 낡은 값을 더한다.**

| 축 | Prometheus 15초 (`records-lag`) | 브로커 5초 (`LEO − committed`) |
|---|---|---|
| booking 정점 | 10,013 (튄다) | **19,870** |
| ticket 정점 | 14,031 (튄다) | **19,505** |
| 곡선 모양 | 톱니 | 단조 감소 |

**따라서 이 리포트의 회복 수치는 전부 브로커 축(`lag-samples-spike.csv`)에서 나왔다.** Prometheus 축은 발급률(`ticketrush_kafka_inbox_total`·`ticketrush_ticket_issue_total`)과 자원 축(CPU·HikariCP·톰캣)에만 썼다 — 그쪽은 서버 카운터라 스크랩 시점 문제가 없다.

> 런북 §10.3 과 Grafana `Kafka Consumer Lag` 패널이 이 지표를 쓴다. **컨슈머가 여러 파티션을 한 스레드로 훑는 구성에서는 적체의 절대량·회복 곡선을 그 패널로 판단하면 안 된다.** "랙이 있다/없다" 정도의 신호로만 쓴다.

---

## 8. 정합성 검증

드레인 완료 후 `@mode='verify'` 로 판정했다. **주입한 23,000건(baseline 3,000 + 스파이크 20,000)이 전부, 정확히 한 번씩 처리됐다.**

| 항목 | 값 | 기대 |
|---|---|---|
| `confirmed` (booking) | **23,000** | = 주입 건수 |
| `sold` (seat) | **23,000** | = 주입 건수 |
| `tickets` | **23,000** | = 주입 건수 |
| `inbox` booking-group | **23,000** | = 주입 건수 |
| `inbox` ticket-group | **23,000** | = 주입 건수 |
| `stray_events` | **0** | `SeatConfirmFailedEvent`·`BookingExpiredEvent` 0건 |
| 잔여 `pending` | 7,000 | 30,000 − 23,000 |

### 8.1 회차 유효성 게이트 — 전부 통과

| 항목 | 결과 |
|---|---|
| `payment-confirmed-topic.DLT` 토픽 | **미생성** |
| `[CRITICAL]` 로그 (booking / ticket / seat) | **0 / 0 / 0** |
| `SEAT_409_003` (`SEAT_CONFIRM_NOT_OWNED`) | **0건** |
| 회복 곡선의 계단형 정체 | 없음 (DLT 백오프 31초가 끼지 않았다) |

### 8.2 eventId 유일성 (완료조건 2)

카운터 절대값이 근거다. `/actuator/prometheus` 직접 조회:

```
ticketrush_ticket_issue_total{result="issued"}                                29001.0
ticketrush_kafka_inbox_total{consumer_group="ticket-group",result="processed"} 29001.0
ticketrush_kafka_inbox_total{consumer_group="booking-group",result="processed"} 29001.0
```

`29,001 = 1(프리플라이트) + 6,000(스모크 3회) + 23,000(본 회차)` 로 **주입 총량과 정확히 일치**한다.

그리고 **`result="duplicate"` 와 `result="already_issued"` 시계열이 아예 존재하지 않는다.** Micrometer 는 지연 등록이라 한 번이라도 증가했으면 시계열이 생긴다. 없다는 것은 **inbox 멱등이 단 한 건도 차단하지 않았다** = eventId 가 전부 유일했다는 뜻이다. 유입이 깎이지 않았다.

### 8.3 이중 발급 0건 (완료조건 4)

`load-test/chaos/verify-inbox.sql` 의 `ticket GROUP BY booking_id HAVING COUNT(*) > 1` 은 **0행**이다.

> ⚠️ 다만 이 쿼리는 스스로 아무것도 증명하지 못한다. `ticket.booking_id` 가 **UNIQUE 제약**이라 어떤 상황에서도 구조적으로 0행이다. 실제 근거는 §8.2 의 `already_issued` 부재와 §8 의 `tickets = 23,000` 정확 일치 쪽이다. 완료조건이 지정한 도구라 그대로 돌리고 결과를 남기되, 한계를 함께 적는다.

---

## 9. 완료조건 대조

| # | 조건 | 결과 |
|---|---|---|
| 1 | `payment-confirmed-topic` 이벤트 주입 스크립트가 `load-test/` 에 있고 반복 실행 가능하다 | ✅ `load-test/chaos/inject-payment-confirmed.sh`. 환경변수 인자·preflight·드레인 대기 포함. 이 회차에서 **5회 반복 실행**(프리플라이트 1 + 스모크 3 + 본 회차 2) |
| 2 | `eventId` 유일성이 보장되어 inbox 멱등에 유입이 깎이지 않는다 | ✅ §8.2 — 카운터 29,001 이 주입 총량과 정확히 일치. `result="duplicate"` 시계열 부재 |
| 3 | 스파이크 유입 후 **backlog 회복시간**이 지표로 산출된다 | ✅ §7.1 — 정점 19,870 → lag 0 까지 **약 8분(479초)**. 드레인율 booking 42.0/s · ticket 85.9/s |
| 4 | 티켓 이중 발급 0건이 `verify-inbox.sql` 로 검증된다 | ✅ §8.3 — 0행. 단 그 쿼리의 구조적 한계를 함께 명시했다 |
| 5 | 측정 환경의 실제 Kafka 파티션 수·consumer concurrency 가 리포트에 명시된다 | ✅ §3 — 배포본에서 읽은 파티션 **3**, 그룹당 컨슈머 **1**. `max.poll.records=20`·`fetch.max.wait.ms=500` 도 함께 |
| 6 | 리포트가 `load-tests/k6/results/{YYMMDD}-{이슈번호}-{슬러그}/` 형식으로 남는다 | ✅ `260729-504-ticket-pipeline-backlog/` |

---

## 10. 한계

- **단일 EC2 에 앱 8개 + Kafka·MySQL·Redis·관측 스택이 동거한다**(2 vCPU). 절대 수치에 그 포화가 섞인다. 이 회차의 산출물은 "앱의 성능" 이 아니라 **"이 구성의 최대치"** 다.
- **결제 API 를 타지 않았다.** payment-service 의 승인 처리·DB 쓰기·`PaymentConfirmUseCase` 는 이 측정에 포함되지 않는다. 잰 것은 **`payment-confirmed-topic` 이후 구간**뿐이다.
- **`verify-inbox.sql` 은 이중 발급을 스스로 증명하지 못한다.** 완료조건 4가 지정한 쿼리(`ticket GROUP BY booking_id HAVING COUNT(*) > 1`)는 `ticket.booking_id` 가 **UNIQUE 제약**이라 구조적으로 항상 0행이다. 증적은 되지만 그 자체로는 아무것도 말하지 않는다 — §8 에 대체 근거를 함께 실었다.
- **주입은 실제 발행과 한 가지가 다르다.** 실 경로는 `KafkaDomainEventPublisher` 가 `eventType`·`eventId` 보조 헤더를 함께 붙이지만 컨슈머가 읽지 않아 동작에는 차이가 없다. 다만 "완전히 같은 메시지" 는 아니다.
- **워밍업 기준선이 하나뿐이다.** 스모크 3회로 고원을 판정했지만, 더 오래 돌리면 더 올라갈 여지가 남아 있다.

### 측정 후 코호트를 남겨 두었다 — 시한이 하나 걸려 있다

`LTP-A` 코호트 30,000건을 지우지 않았다(재실행 가능하게 두는 편이 낫다). 그중 **7,000석이 아직 `HOLD` 이고 `hold_expired_at` 이 `2026-07-29 13:15:37 UTC`(시딩 +6시간)** 다.

> ⚠️ **EC2 를 끄는 것으로 해소되지 않는다 — 미뤄질 뿐이다.** `SeatStatusScheduler`(60초 주기, tick 당 최대 2,000건)는 인스턴스가 떠 있을 때만 돌지만, **13:15Z 이후에 인스턴스를 다시 켜면 만료 시각이 이미 과거라 기동 직후 첫 tick 에 7,000석이 한꺼번에 해제된다.** 좌석 1건당 `SeatHoldExpiredEvent` 가 나가므로 **그때 다른 회차를 돌리고 있으면 오염된다.**
>
> (측정 종료 시점에 EC2 가 내려가 있어 이 정리를 실행하지 못했다. **다음 기동 때 측정보다 먼저 해야 한다.**)

지우려면 `@mode='reset'` 이 아니라 코호트 삭제가 필요하다. `cleanup_load.sql` 에 `LTP-%`/`LT-P%` 정리를 추가해 뒀지만 그 파일은 **다른 이슈의 `LOADTEST-%` 코호트까지 전부 지운다.** 이 코호트만 지우려면 그 절만 떼어 실행한다:

```sql
DELETE t FROM ticket t JOIN booking b ON b.booking_id = t.booking_id
 WHERE b.booking_number LIKE 'LT-P%';
DELETE FROM booking WHERE booking_number LIKE 'LT-P%';
DELETE s  FROM seat s        JOIN performance p ON p.performance_id = s.performance_id
 WHERE p.title LIKE 'LTP-%';
DELETE sl FROM seat_layout sl JOIN performance p ON p.performance_id = sl.performance_id
 WHERE p.title LIKE 'LTP-%';
DELETE FROM performance WHERE title LIKE 'LTP-%';
DELETE FROM inbox WHERE event_type = 'PaymentConfirmed'
   AND consumer_group IN ('booking-group', 'ticket-group');
```

---

## 11. 여기서 나온 후속 과제

**측정 회차이므로 아무것도 고치지 않았다. 근거만 남긴다.**

**(1) `concurrency` 를 올리려면 코드를 고쳐야 한다 — 이슈 본문의 전제가 틀렸다.**

이슈는 "브로커 재구성 없이 `concurrency` 만 3으로 올려 3배를 확보할 수 있다" 고 적었다. 방향은 맞지만 **환경변수로는 켤 수 없다.** `spring.kafka.listener.concurrency` 는 Spring Boot 자동설정 팩토리가 읽는 값인데, 이 프로젝트는 `common` 의 `KafkaConfig` 에서 `kafkaListenerContainerFactory` 를 직접 만들고 그 프로퍼티를 읽지 않는다. 즉 `chaos/*.override.yml` 방식(환경변수 오버레이)으로는 비교 회차조차 만들 수 없고, **`common` 을 고치면 전 서비스의 모든 컨슈머에 동시에 적용된다.** 별도 이슈로 연다.

**(2) 진짜 구속 조건은 `booking-group` 의 동기 HTTP 왕복이다.**

같은 토픽·같은 파티션 수·같은 concurrency 인데 두 그룹의 드레인율이 2배 갈린다. 차이는 처리 내용뿐이다.

```
ticket-group   : inbox tx → ticket INSERT
booking-group  : inbox tx → booking UPDATE → bookingNumber 재조회
                 → seat-service HTTP 왕복 → seat UPDATE+SELECT → Redis forceRelease 1왕복
                 → SSE submit
```

`concurrency` 를 올리는 것은 이 직렬 사슬을 그대로 둔 채 사슬을 여러 개 돌리는 것이다. 사슬 자체를 줄이는 쪽(SOLD 확정을 이벤트로 뒤집기)이 더 큰 이득일 수 있으나, 그건 #399 가 걸린 MSA 캡슐화 규율과 얽힌다.

**(3) 두 컨슈머가 같은 2 vCPU 를 두고 경합한다.**

`ticket-group` 이 0에 도달한 뒤 `booking-group` 의 드레인율이 눈에 띄게 올랐다(§7). 팬아웃 소비자를 늘리면 서로의 처리율을 깎는다는 뜻이고, 이 구성에서 `concurrency` 를 올려도 3배가 그대로 나오지 않을 근거다. 단일 인스턴스 동거의 대가다.

**(4) `cleanup_load.sql` 이 `LTC-%`(#403) 코호트를 정리하지 않는다.**

이 회차에서 `LTP-%` 를 추가하며 확인했다. #403 의 `LTC-*` 는 여전히 어느 정리 대상에도 걸리지 않는다. 범위 밖이라 손대지 않았다.

---

## 증적 파일

| 파일 | 내용 |
|---|---|
| `metadata.txt` | 수치 SSOT. report 는 여기서 인용한다 |
| `smoke-timeline.txt` | 스모크 3회 원문(워밍업 근거) |
| `main-timeline.txt` | 본 회차 baseline·스파이크 원문. `[drain]` 줄이 회복 곡선의 원자료 |
| `lag-samples-spike.csv` | 위 `[drain]` 줄을 CSV 로 정리한 것 |
| `seed-verify.txt` | 시딩·리셋·검증 SELECT 출력(완료조건 1) |
| `verify-inbox-output.txt` | `load-test/chaos/verify-inbox.sql` 실행 결과(완료조건 4). 첫 쿼리가 0행이라 출력이 없는 것이 결과다 — 파일 머리말에 설명을 붙였다 |
| `timeseries-*.json` | Prometheus 15초 스크랩 덤프. 접미사 `-smoke`(07:09~07:15:30Z) / `-main`(07:15:30~07:32Z). `ticket-issue-rate`·`inbox-rate` 는 `dump-timeseries.py` 의 목록에 없어 따로 떴다 |
| `grafana-capture-links.md` | Explore 링크 2장(쿼리·UTC 창이 URL 에 박혀 있다) |
| `graph-drain-rate.png` | 두 그룹 처리율. baseline 9/s → 스파이크 ticket 86/s·booking 39/s → ticket 종료 후 booking 45/s |
| `graph-resources.png` | 회복 구간 호스트 CPU·HikariCP 대기·seat-service 톰캣 스레드 |

### 캡처 판독 메모

- **그래프의 시각은 KST 다(브라우저 시간대). 이 리포트의 시각은 전부 UTC 다.** `16:16` = `07:16Z`. 9시간을 빼고 읽는다.
- `graph-drain-rate.png` 의 **범례에 있는 노란색 `ticket-group` 선이 안 보이는 것은 정상이다.** 파란색 `{result="issued"}` 와 **완전히 겹친다** — `ticket-group` 이 처리한 이벤트 1건이 곧 티켓 1장이라 두 값이 정의상 같다. 선이 빠진 게 아니다.
- `graph-resources.png` 에서 **HikariCP(7개 인스턴스)와 톰캣 스레드는 바닥에 눌려 서로 구분되지 않는다.** CPU 축이 0~90 인데 그 둘은 각각 0과 1이라 #403 이 기록한 "쿼리 단위가 다르면 한 그래프에 넣지 않는다" 함정에 그대로 걸린다. 다만 이 그림이 말하려는 것이 **"CPU 가 80% 인 동안 두 풀은 바닥에 붙어 있었다"** 이므로 그 자체는 읽힌다. 정확한 값은 `metadata.txt` 의 `HIKARI_PENDING_MAX=0`·`TOMCAT_BUSY_SEAT_MAX=1` 이다.

> **그래프 PNG 는 이 회차의 완료조건이 아니다.** #403 은 완료조건 6이 "Grafana 그래프 캡처" 였지만 #504 의 완료조건 6은 리포트 디렉토리 형식이다. 수치의 원자료는 `lag-samples-spike.csv`(5초 폴링, 브로커의 `LEO − committed`)와 `timeseries-*.json`(Prometheus 15초 스크랩) 두 벌이고 둘 다 커밋돼 있다. 그림이 필요하면 `grafana-capture-links.md` 의 링크를 열어 캡처해 `graph-*.png` 로 같은 디렉토리에 넣으면 된다 — 링크에 쿼리와 측정 창이 이미 박혀 있다.
