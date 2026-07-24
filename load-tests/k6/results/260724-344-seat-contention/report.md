# #344 좌석 예매 동시성 측정 — oversell 0건·차단율 99.77%

> 측정일 2026-07-24 (KST) · 대상 EC2 배포본(`6398a9d`) · 런북 [docs/load-test-guide.md §10](../../../../docs/load-test-guide.md)

## 1. 목적

동일 좌석에 예매 요청을 집중시켜 **oversell 0건**을 실측으로 확인하고, **차단율·성공률**을 메트릭으로 산출한다. 이슈 제목은 "락 경합 차단율"이지만 이 경로에서 Redisson 락 경합은 구조적으로 발생하지 않는다(§2) — 그 사실 자체를 실측으로 확정하고, 실제 방어선이 어디인지, 파이프라인의 1차 제약이 무엇인지를 **유입 축과 처리 축을 갈라** 기록한다.

## 2. 측정 설계

### 2.1 경로 — POST와 HOLD 사이에 비동기 구간이 둘이다

```
[유입 축]  POST /api/v1/booking  → booking INSERT + outbox INSERT (한 트랜잭션, #471)
[처리 축]  → OutboxRelayScheduler  @Scheduled(fixedDelay=5000), batch-size=100
             → Kafka booking-created-topic (key = bookingId)
               → seat-service BookingCreatedEventListener  @KafkaListener(groupId=SEAT), concurrency 1
                 → InboxService.runIfFirst → SeatFacade.tryLockSeat → SeatLockUseCase(tryLock 대기 0, lease 5분)
```

201 응답은 "예매 생성됨"이지 "좌석 선점됨"이 아니다. **HTTP 지표로는 락도 홀드 지연도 볼 수 없다.**

### 2.2 락 경합이 0인 이유 (실측으로 확인됨)

컨슈머 스레드가 1개이고(`KafkaConfig`에 `setConcurrency` 없음 → 기본 1), Redisson `RLock`은 `(clientUUID:threadId)` 기준 **재진입** 락이다. 같은 스레드가 1,324건을 순차 처리하면 `tryLock`은 실패하지 않고 재진입으로 성공하므로, `tryLock == false` 경로에서만 오르는 `seat_lock_contention` 카운터는 **시리즈 자체가 생성되지 않는다.** 이번 측정에서 실제로 그랬다.

따라서 **차단율은 `seat_hold{result="unavailable"}` 비율로 산출한다.** 락 경합률로 산출하면 항상 0%가 나와 "방어선이 없다"로 오독된다.

### 2.3 프로파일

`VUS=100 / RAMP=5s / STEADY=6m`. 램프를 길게 잡으면 좌석이 램프업 도중 HOLD로 확정돼 경합 창에 소수 VU만 참여한다 — 짧은 램프로 실제 버스트를 만든다. STEADY 6분은 완료조건("각 부하 단계 최소 5분 유지")을 충족한다.

## 3. 실측 결과

| 항목 | 값 |
|---|---|
| 실행 일시 / 배포 이미지 | 2026-07-24 · `6398a9d` (전 서비스 동일, #471 outbox 포함) |
| 측정 창 (UTC) | 12:31:17 ~ **12:38:22** (7분 05초, STEADY 6분) |
| 창 종점 출처 | k6 종료(12:37:53) 후 outbox `PENDING/FAILED = 0` 첫 관측 시각 |
| 프로파일 | VUS 100 / RAMP 5s / STEADY 6m |
| 총 요청 (http_reqs) | 95,684 |
| 201 / 409 (분모 = iterations 95,683) | 1,324 (1.38%) / 94,359 (98.61%) |
| 정상경합 제외 에러율 (§4) | **0.00%** (0 / 95,684) |
| **oversell** | **0** — 동시 보유 좌석 최대 1건 |
| **차단율** (unavailable / total) | **99.77%** (1,321 / 1,324) |
| 홀드 성공률 | 0.23% (3 / 1,324) |
| **락 경합** | **0** (시리즈 미생성) |
| 유입 축 — RPS / p95 / p90 / med | 258.29/s (peak 353.05) / 911.79ms / 726.94ms / 306.15ms |
| 처리 축 — backlog 피크 / 릴레이 발행률 / 컨슈머 랙 피크 / 소비율 | 634 / 20.98·s⁻¹ / **14** / 20.54·s⁻¹ |
| 1차 제약 판정 | **릴레이 발행 상한(20/s)** — 컨슈머 랙이 최대 14로 거의 자라지 않는다 (§6) |
| 호스트 (#465) | CPU peak 99.87% / avg 77.55%, MemAvailable min 2.85 GiB |

> **분모가 둘이다.** `http_reqs`(95,684)에는 `setup()`의 로그인 1건이 포함되고, `seat_accepted`·`seat_conflict`의 분모는 `iterations`(95,683)다. 그래서 1,324 + 94,359 = 95,683으로 총 요청보다 1 적다. `http_req_failed`의 분모는 95,684다.

**핵심 정합**: `seat_hold{success} 3 + unavailable 1,321 = 1,324` = **생성된 예매 수와 정확히 일치**한다. 발행된 이벤트가 하나도 유실되지 않고 전부 DB 체크에 도달했다는 뜻이다.

## 4. 에러율 집계 기준 — 정상 경합은 실패가 아니다

동일 좌석에 1,324건이 몰려 1건만 성공하는 것은 설계된 동작이다. 그대로 두면 실패율이 99%대로 집계돼 진짜 장애(5xx·타임아웃)가 그 안에 묻힌다. `seat-contention.js`는 임계값을 완화하는 대신 409만 기대 응답으로 옮긴다.

```js
http.setResponseCallback(http.expectedStatuses(200, 201, 409));
```

그 결과 `http_req_failed = 0.00% (0/95,684)`이며, 이 값은 **5xx·401·타임아웃이 실제로 0건**이었다는 뜻이다(409를 눈감아 준 값이 아니다). 200을 함께 넣는 이유는 이 콜백이 run 전역이라 `setup()`의 로그인까지 덮기 때문이다.

## 5. HOLD 3회는 이중 선점이 아니라 시간차다

`seat_hold{success}`가 1이 아닌 **3**이다. 동시 3중 선점이 아니라, **HOLD 획득 → `hold_expired_at` 5분 TTL 만료로 해제 → 남은 큐가 재획득**을 3회 반복한 것이다. 6분 부하가 5분 TTL보다 길어 필연적으로 생긴다.

oversell 0의 근거는 셋이다.

1. `max_over_time(ticketrush_seat_held[8m]) = 1` — 측정 내내 동시 보유 좌석은 최대 1건.
2. DB 정합 — booking 1,324행 = **CANCELED 1,321**(보상) + **EXPIRED 3**(HOLD 획득분의 TTL 만료). 잔여가 없다.
3. 구조 — `Seat.hold()`가 `seatStatus == AVAILABLE`을 요구하고 `@Version`(#427)이 커밋 시점 충돌을 막는다. AVAILABLE에서 두 커밋이 동시에 성립할 수 없다.

> **한계**: `seat_held`는 15초 스크랩 게이지라 15초보다 짧은 동시 보유는 원리상 포착하지 못한다. 그 구간의 보증은 3번의 구조적 근거와 `SeatHoldConcurrencyTest`(seat-service, 실 MySQL·Redis에 20스레드 동시 투입)가 담당한다 — 부하 테스트로는 만들 수 없는 락 경합을 그 테스트가 스레드를 갈라 검증한다.

## 6. 1차 제약은 컨슈머 병렬도가 아니라 릴레이였다

이슈는 "1차 병목은 Redisson 락이 아니라 consumer concurrency 1일 가능성이 높다"는 가설을 세웠다. **실측은 이를 반증한다.**

| 근거 | 값 |
|---|---|
| 컨슈머 랙 피크 | **14** (파티션별 8/0/14) — 사실상 자라지 않음 |
| 릴레이 발행률 피크 (booking-service 단독) | 20.98/s — 이론 상한 `100건 / 5초 = 20/s`와 일치 |
| outbox backlog 피크 | 634 |

유입은 258 rps인데 Kafka로 나가는 속도는 21/s에서 막힌다. 컨슈머는 그보다 빨라 랙이 쌓이지 않는다. 즉 **파이프라인을 조이는 것은 소비 병렬도가 아니라 릴레이의 배치 주기다.** 컨슈머 동시성을 올려도 이 구간에서는 개선되지 않는다.

### 6.1 릴레이 발행 증폭 3.09배

booking-service 릴레이가 **4,090건**을 발행했는데 outbox 행은 **1,324건**뿐이다. seat-group Inbox가 그 차이를 그대로 받아냈다 — `processed 1,324` / `duplicate 2,763`. 원본 수치는 `counters-snapshot.txt`(측정 창 종점의 카운터 총량)에 있다.

> 1,324 + 2,763 = 4,087로 발행 4,090과 **3건 차이**가 난다. 스크랩(15초)과 창 종점이 정확히 겹치지 않아 마지막 발행분이 컨슈머 카운터에 아직 반영되지 않은 것으로 본다. 증폭 배율은 이 오차에 영향받지 않는다(4,090/1,324 = 3.09, 4,087/1,324 = 3.09).

원인은 `OutboxRelayService.relayBatch()`다. `RELAY_TARGET_STATUSES`(= `PENDING`, `FAILED`) 행을 batch-size만큼 조회해 **비동기** 발행하고, SENT 전이는 프로듀서 콜백의 `OutboxStatusUpdater.markSuccess`(REQUIRES_NEW)에서만 일어난다. **in-flight 표시가 없어** 콜백 커밋이 다음 5초 폴링보다 늦으면 `findByAggregateTypeInAndStatusInOrderByIdAsc(..., PageRequest.of(0, batchSize))`가 **같은 행을 다시 집어** 재발행한다. outbox 행의 `retry_count`는 전부 0 — 발행 실패의 재시도가 아니라 **정상 발행의 중복**이다.

이건 버그가 아니라 클래스 javadoc이 명시한 at-least-once 설계 그대로다("중복은 컨슈머가 eventId로 멱등 처리한다"). #346이 증명한 유실 0(중복 허용)과 #347이 증명한 Inbox 중복 차단이 **경합 부하에서 실제로 맞물려 도는 것을 이번에 관측**했다. 이번 측정이 더하는 것은 그 배율과 비용이다 — 단일 컨슈머 스레드가 유효 처리량의 **68%**(2,763 / 4,087)를 중복 차단에 쓴다.

## 7. 해석·한계

- **p95 911.79ms로 임계값 `p(95)<800`을 초과했다(FAIL).** 다만 피크 구간 호스트 CPU가 **99.87%**였다. 이 수치는 앱 단독 특성이 아니라 **단일 EC2에 앱 9개 + Kafka + MySQL + Redis + 관측 스택이 함께 뜬 구성의 포화**가 섞인 값이다. 앱 최적화 대상으로 읽으면 오독이다.
- 409 경로는 booking-service의 사전 체크(`seat` 테이블 직접 SELECT)에서 끊긴다. 좌석이 HOLD로 커밋된 뒤부터 걸리므로, **HOLD 커밋 전 윈도우에 들어온 요청은 전부 201을 받고 컨슈머까지 흘러가 `unavailable`에서 걸린다.** 1,324건이 그 윈도우의 크기다.
- 이번 측정은 릴레이가 유입을 정형화한 뒤의 경합을 본다. `booking-service`가 즉시 발행(kafka 모드)이던 시절의 수치와는 직접 비교할 수 없다.
- 컨슈머 동시성을 올리면(`setConcurrency > 1`) 락 경합이 처음으로 실제 발생한다. 그때의 동작은 `SeatHoldConcurrencyTest`가 회귀 그물로 고정해 두었다.

## 8. 증적 파일

| 파일 | 내용 |
|---|---|
| `k6-summary.txt` | k6 실행 요약 원문(임계값 판정 포함) — 유입 축 수치의 SSOT |
| `metadata.txt` | 실행 파라미터·창·전 수치 키=값 |
| `counters-snapshot.txt` | 창 종점(12:38:22Z)의 카운터 총량 — §6.1 발행/중복 수치의 원본 |
| `timeseries-seat-hold.json` | `sum(ticketrush_seat_hold_total) by (result)` |
| `timeseries-outbox-backlog.json` | `ticketrush_outbox_backlog{instance="booking-service:8090"}` |
| `timeseries-consumer-lag.json` | `kafka_consumer_fetch_manager_records_lag{topic="booking-created-topic"}` |
| `timeseries-inbox-rate.json` | `sum(rate(ticketrush_kafka_inbox_total{consumer_group="seat-group"}[1m])) by (result)` |
| `timeseries-relay-rate.json` | booking-service 릴레이 발행률 |
| `timeseries-k6-rps.json` | `sum(rate(k6_http_reqs_total[1m]))` |
| `graph-*.png` (아래 4종) | Grafana Explore 캡처. 시간 범위 2026-07-24 21:31~21:39 KST |
| `graph-seat-hold.png` | `sum(ticketrush_seat_hold_total) by (result)` — success 계단 vs unavailable 상승 |
| `graph-outbox-backlog.png` | backlog 피크 634 → 0 소진 |
| `graph-consumer-lag.png` | 컨슈머 랙 피크 14 — 거의 평평 (§6의 반증 근거) |
| `graph-inbox-rate.png` | duplicate가 processed를 압도 (§6.1의 증폭 근거) |
