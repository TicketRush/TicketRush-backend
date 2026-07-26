# #345 대량 만료 청크 트랜잭션 측정 — 최장 트랜잭션 42배 단축, 커넥션 풀 압박은 없었다

> 측정일 2026-07-25 (KST) · 대상 EC2 배포본(`6398a9d`) · 런북 [docs/load-test-guide.md §11](../../../../docs/load-test-guide.md) · 준비 PR [#487](https://github.com/TicketRush/TicketRush-backend/pull/487)

## 1. 목적

만료 HOLD 좌석 해제 fallback의 **청크 트랜잭션 분할 효과**를 단일 트랜잭션 대비 정량 비교한다. 이슈는 세 가지를 물었다 — 트랜잭션 지속시간, 락 점유, 커넥션 풀 압박. **셋 중 둘은 수치가 나왔고, 하나(커넥션 풀)는 이 경로에서 구조적으로 발생하지 않는다는 것이 실측으로 확인됐다.** 그 사실 자체를 §5에 기록한다.

k6는 쓰지 않는다. 이 경로에 HTTP 진입점이 없다 — 만료 좌석을 시딩하고 `SeatStatusScheduler`가 그것을 소진하는 과정을 관측한다.

## 2. 측정 설계

### 2.1 이슈가 지정한 주 지표는 성립하지 않는다

이슈는 1만 건 시나리오의 주 지표를 "`seat_held` Gauge의 tick별 잔량 감소 곡선"으로 잡았다. **그 게이지로는 이 사건이 보이지 않는다.**

```java
// SeatRepository.countHeldSeats — ticketrush_seat_held 의 원천
select count(s) from Seat s where s.seatStatus = :hold and s.holdExpiredAt > :now
```

`> :now`다. 즉 **미만료** HOLD만 센다. 만료됐으나 아직 해제되지 않은 좌석 — 정확히 이 측정이 재려는 적체 — 은 처음부터 게이지 밖이다.

| 관측 | 값 |
|---|---|
| `max_over_time(ticketrush_seat_held[15m])` — A1·A2 반복 창 | **0.00** |
| 같은 값 — B1 창(만료 10,000건 소진) | **0.00** |
| 같은 창에서 실제 소진된 만료 좌석 | 2,000×9회 + 10,000 |

곡선이 평평한 게 아니라 **시리즈가 그 사건을 담지 않는다.** #484가 고친 스케줄러 스레드 굶주림과 무관한, 지표 정의의 공백이다.

### 2.1.1 같은 좌석 2,000건이 2,000으로도, 0으로도 읽힌다

`graph-seat-held-semantics.png`(13:20~14:56 KST)에는 **04:26~04:33Z 구간에만 2,000짜리 사각 펄스**가 있고 앞뒤는 0이다. 우연이 아니라 이 게이지의 의미를 그대로 드러낸 구간이다.

| 시각 (UTC) | 코호트 상태 | `seat_held` |
|---|---|---|
| 04:24:39 시딩 (최초 시도, `NOW()` = KST로 시딩) | HOLD · `hold_expired_at`이 앱(UTC) 기준 **9시간 미래** = 미만료 | **2,000** |
| 04:32:48 재시딩 (`UTC_TIMESTAMP()`로 정정) | 같은 좌석이 HOLD · **이미 만료** = 해제 대기 적체 | **0** |

**좌석 2,000건은 그대로인데 게이지만 2,000 → 0으로 바뀐다.** `hold_expired_at > now` 조건이 만료 순간 좌석을 시리즈에서 탈락시키기 때문이다. 이 측정이 재려는 것은 아래쪽 행(만료 적체)이고, 그 행에서 게이지는 0이다.

> 이 펄스는 §8의 시계 함정이 남긴 부산물이다 — 잘못 시딩된 코호트가 "미만료"로 존재했던 8분이 우연히 대조군이 됐다. 펄스 종료 시각(04:32:48Z)이 정정 시드의 **완료** 시각과 맞는 이유는, 그 시드의 리셋에서 느린 outbox DELETE 두 개가 좌석 UPDATE보다 앞에 있어 좌석 해제가 배치 끝에 일어났기 때문이다(§8의 시딩 2분 30초). PR #487이 `ticketrush.seat.hold.expired_backlog` 게이지를 추가한 근거가 이 수치이며, 그 게이지는 아직 배포되지 않았다(CD는 `main` push 트리거다). **이번 B1의 적체 곡선은 SQL 폴링으로 기록했다**(§6, `drain-b1.csv`).

### 2.2 단일 트랜잭션 비교군은 코드 변경이 아니다

`chunkSize` × `maxChunks`가 곧 (트랜잭션 범위) × (tick당 청크 수)다. `chunk-size=2000` / `max-chunks=1`이면 `SeatReleaseExpiredUseCase`의 루프가 한 번만 돌아 2,000건이 단일 트랜잭션으로 커밋된다. 벤치용 브랜치도 토글 코드도 필요 없다 — compose override 하나뿐이다.

**설정 확증은 로그가 한다.** actuator/env는 노출 대상이 아니므로(health·info·prometheus 3개), 처리 상한 도달 경고가 유일한 확증 수단이다. 두 arm 모두 실제 값을 그대로 찍었다.

```
A1  만료 좌석 처리 상한(chunkSize=25 x maxChunks=80)에 도달해 ...
A2  만료 좌석 처리 상한(chunkSize=2000 x maxChunks=1)에 도달해 ...
```

10,000건 단일 트랜잭션 arm은 돌리지 않았다 — seat-service `mem_limit`이 640m이고, 단일 트랜잭션이 ShedLock `lockAtMostFor=2m`를 넘기면 락이 풀려 중복 실행 창이 열린다. 2,000건이 tick당 처리 상한과 같은 크기라 그 지점의 A/B가 정직한 대조다.

### 2.3 측정 전 인덱스 DDL을 적용했다 — 이전 수치는 쓸 수 없다

사전 점검에서 **prod `seat` 테이블에 `idx_seat_status_hold_expired_at`이 없었다.** prod는 `ddl-auto=validate`라 `@Table`의 `@Index` 부재를 검출하지 못한다(#296 관행, `Seat` javadoc). 그 상태로 재면 청크 조회가 tick당 80회 풀스캔(26,000행)이 되어 측정값이 인덱스 결함과 섞인다. 04:24:05Z에 적용했다.

```sql
ALTER TABLE seat ADD INDEX idx_seat_status_hold_expired_at (seat_status, hold_expired_at),
  ALGORITHM=INPLACE, LOCK=NONE;
```

적용 후 실행계획: `type=range`, `key=idx_seat_status_hold_expired_at`, `Extra="Using where; Using index; Using filesort"` — 커버링 인덱스로 seek하고, `ORDER BY seat_id`만 filesort로 남는다.

### 2.4 지속시간은 샘플링이 아니라 `performance_schema`로 잰다

청크 트랜잭션은 실측 수십 ms라 `innodb_trx` 1초 폴링으로는 **원리상** 잡히지 않는다(샘플 사이에 끝난다). 대신 `events_transactions_summary_by_thread_by_event_name`을 **tick 직전 truncate → tick 직후 read** 해서 그 창의 최댓값을 정확히 얻는다. `threads.PROCESSLIST_HOST`로 seat-service 커넥션만 걸러 다른 서비스의 릴레이·인박스 트랜잭션을 배제했다.

## 3. 실측 결과

| 항목 | A1 청크 25×80 | A2 단일 2000×1 |
|---|---|---|
| 만료 코호트 | 2,000 | 2,000 |
| **최장 트랜잭션 (ms)** | 58.8 / 60.5 / **76.0** / 77.1 / 379.0 | 2193.7 / **2547.5 · 3875.9** / 5513.1 |
| 중위 | **76.0 ms** | **3,211.7 ms** |
| 평균 트랜잭션 (ms) | 16.6 ~ 32.4 | 26.0 ~ 47.8 |
| 창 내 트랜잭션 수 | 182 ~ 206 | 110 ~ 145 |
| tick 총 소요 (s) | 2.273 / 2.523 / 2.901 / 2.938 / 5.798 | 2.198 / 2.552 / 3.884 / 5.528 |
| `hikaricp_connections_pending` 피크 | **0** | **0** |
| `hikaricp_connections_active` 피크 / idle | 1 / 9 | 1 / 9 |
| 관측된 락 대기 (`data_lock_waits`·`LOCK WAIT`) | 0 | 0 |
| n | 5 | 4 |

**최장 트랜잭션 지속시간 중위값 3,211.7 ms → 76.0 ms, 42.3배(97.6%) 단축.**

> **A1의 379.0 ms는 이상치다.** 그 회차만 직전에 구버전 시드(회차당 2분 30초 소요, §8)가 DB를 점유한 뒤였다. 제외하면 A1 중위 68.3 ms, 배율 47배. 리포트 수치는 **이상치를 포함한** 76.0 ms를 쓴다.

**계측 정합 확인**: A2의 `max_ms`는 매 회차 tick 총 소요와 거의 같다(예: 5513.1 ms vs 5.528 s). 단일 트랜잭션이 tick을 통째로 차지한다는 뜻이고, P_S 측정이 실제로 그 트랜잭션을 잡았다는 증거다.

**tick 총 소요는 두 arm이 겹친다**(A1 2.27~5.80 s, A2 2.20~5.53 s). 청크 분할은 총 처리시간을 늘리지 않고 **같은 작업을 80개의 짧은 트랜잭션으로 쪼갠다.** 처리량을 대가로 지불하지 않는다.

호스트 CPU는 max 65.9% / avg 34.2%로 포화가 아니었다(#344 측정의 99.87%와 다르다). 절대 수치가 상대적으로 깨끗하다.

## 4. 락 점유 — 최장 트랜잭션이 곧 락 보유 상한이다

`SeatReleaseExpiredChunkProcessor`는 청크 안에서 좌석을 한 건씩 조건부 UPDATE한다. 첫 좌석에 걸린 행 잠금은 **청크 커밋까지** 유지된다 — `application.yml`의 주석이 `chunk-size: 25`의 근거로 든 바로 그 성질이다. 따라서 **트랜잭션 지속시간의 최댓값이 곧 특정 좌석 행이 잠긴 시간의 상한**이다.

| | 락 보유 상한 (중위) |
|---|---|
| 청크 25건 | **76 ms** |
| 단일 2,000건 | **3,212 ms** |

`trx-sampler.sh`가 잡은 `max_rows_locked`는 청크 arm에서 최대 46, B1에서도 46이다(청크 25건 + 인덱스·PK 엔트리). 단일 arm에서는 시딩 구간에 57,625까지 올라가지만 그건 시드 자신의 대량 UPDATE다.

> **`confirmSoldById` 블로킹은 직접 측정하지 못했다.** 결제 확정 이벤트를 대량 생성할 수단이 없다(가이드 §9.1이 같은 이유로 `ticket-group`을 보조 증적으로 돌린 전례). 이번 측정에서 `LOCK WAIT`·`data_lock_waits`는 **0**이다 — 만료 처리와 경합하는 트래픽이 없었으므로 당연한 결과이고, "블로킹이 없다"는 뜻이 아니다. 확인된 것은 **잠금이 유지되는 시간의 상한이 42배 줄었다**는 사실이며, 그 시간 안에 같은 좌석의 결제 확정이 들어오면 그만큼 기다린다는 것은 코드 구조가 보장한다. 추정을 수치로 위장하지 않는다.

## 5. 커넥션 풀 압박은 이 경로에서 구조적으로 발생하지 않는다

이슈는 "커넥션 풀 고갈 회피"를 기대 효과로 적었다. **실측은 두 arm 모두 `hikaricp_connections_pending = 0`이다.**

원인은 단순하다 — 만료 fallback은 `@Scheduled` 스케줄러 스레드 **하나**가 도는 단일 스레드 경로다. 커넥션을 한 개만 쓴다(실측 `active` 피크 1, `idle` 9). 풀 크기가 10이므로 단일 트랜잭션이 5초를 물고 있어도 **대기할 다른 요청이 없다.**

> Prometheus 스크랩은 15초라 2~6초짜리 tick을 통째로 놓친다. 그래서 actuator를 **1초로 직접 폴링**해 확인했다(`hikari-{a1,a2,b1}.csv`). 1초 해상도에서도 `pending`은 전 구간 0이다.

**따라서 "청크 분할로 커넥션 풀 고갈을 회피한다"는 서술은 이 경로·이 규모에서 실측으로 뒷받침되지 않는다.** 풀 압박을 만들려면 만료 처리와 동시에 다른 요청들이 커넥션을 다투어야 하고, 그건 이 시나리오 밖이다. **청크 분할의 실측 이득은 락 보유 시간 하나로 좁혀진다** — 그리고 그 하나가 42배다.

## 6. B1 — 만료 10,000건의 적체 해소 곡선

`chunk-size=25` / `max-chunks=80`(운영값) 그대로, 만료 10,000건을 시딩했다. 시딩 05:42:54Z.

| tick | 시작 (UTC) | 완료 (UTC) | 소요 | 해제 | 잔여 적체 |
|---|---|---|---|---|---|
| 1 | 05:43:54.436 | 05:43:59.478 | 5.042 s | 2,000 | 8,000 |
| 2 | 05:44:59.482 | 05:45:03.395 | 3.913 s | 2,000 | 6,000 |
| 3 | 05:46:03.399 | 05:46:06.605 | 3.206 s | 2,000 | 4,000 |
| 4 | 05:47:06.613 | 05:47:09.340 | 2.727 s | 2,000 | 2,000 |
| 5 | 05:48:09.345 | 05:48:12.088 | 2.743 s | 2,000 | **0** |

- **정확히 5 tick, tick당 정확히 2,000건.** 이슈가 예측한 상한 동작(`chunkSize` × `maxChunks` = 2,000/tick)이 그대로 관측됐다. 매 tick 처리 상한 경고가 찍혔다.
- 시딩 → 완전 소진 **5분 18초**. tick 간격은 60.00 s로 일정하다(`fixedDelay`는 **완료 시각** 기준이므로 tick 소요가 그만큼 뒤로 밀린다 — 표의 시작 시각이 매 tick 3~5초씩 늦어지는 이유다).
- 5 tick 전체 트랜잭션: **6,250건, 최장 182.9 ms, 평균 11.9 ms.** 400개 청크(80×5) + 릴레이·게이지 트랜잭션이 섞인 수치다. 최장값이 A1 단발 회차(58.8~77.1 ms)보다 큰 것은 관측 창이 5분으로 길어 꼬리가 잡힌 결과다.
- `hikaricp_connections_pending` 전 구간 **0**, `active` 피크 1. 5분간 지속된 적체에서도 풀 압박은 없다(§5).
- 호스트 CPU max 74.9% / avg 30.8%.

### 6.1 부수 발견 — 만료 이벤트 생성률이 릴레이 발행 상한을 초과한다

적체 해소는 순조로웠지만 **outbox는 반대 방향으로 쌓였다.**

| | 값 |
|---|---|
| `ticketrush_outbox_backlog` 피크 (seat-service) | **5,200** |
| 릴레이 발행률 피크 | **20.00/s** (= `batch-size` 100 / `fixedDelay` 5s 이론 상한) |
| tick당 이벤트 생성률 | 2,000건 / 60초 = **33.3/s** |

생성 33.3/s 대 발행 20/s다. tick마다 약 800건씩 밀려 5 tick 동안 단조 증가했고, 좌석 적체가 0이 된 05:48:13Z 시점에도 outbox에는 5,186건이 남아 있었다. 10,000건 전량 발행에는 좌석 해제가 끝난 뒤로도 20/s × ≈500초가 더 필요하다.

**정합성 문제는 아니다** — 트랜잭셔널 Outbox의 at-least-once 설계대로 전부 발행되고, booking-service가 10,000건을 모두 EXPIRED로 전이했다(`bk_expired=10000`, `bk_pending=0`). 다만 **"만료 좌석이 AVAILABLE로 돌아온 시점"과 "예매가 EXPIRED로 보이는 시점"이 최대 8분 이상 벌어진다.** 릴레이 발행 상한 20/s는 #344가 이미 파이프라인의 1차 제약으로 지목한 값이고, 대량 만료는 그 상한을 정면으로 때리는 경로다.

그 지연은 단순한 가시화 지체가 아니다. `PaymentConfirmUseCase:82-85`의 만료 가드가 **이벤트 도착에 의존**하고(`expiredBookingRepository.existsByBookingId`, `BookingExpiredEvent`로 채워진다), 그 이벤트는 릴레이를 **두 홉**(seat→booking, booking→payment) 타야 한다. 창 안에서 결제가 들어오면 가드를 통과해 PG 승인이 실행되고(과금 발생), 이어지는 `SeatConfirmSoldUseCase:28`의 `confirmSoldById`가 이미 AVAILABLE·`booking_number = NULL`인 좌석을 만나 `updated = 0` → `SEAT_NOT_AVAILABLE`(`:45`) → 재시도 → DLT로 간다. **과금은 됐고 `Payment`는 COMPLETED인데 좌석이 확정되지 않는 상태다.** 창 자체는 릴레이 속도와 무관하게 존재하지만(주석이 "best-effort"라 적는 이유다) 릴레이 상한이 그것을 초 단위에서 분 단위로 늘린다.

> **후속: #489** (`[공통]` / `fix`). 발행 처리량을 생성률 이상으로 올리는 것이 그 이슈의 범위다. 가드를 이벤트 의존에서 떼는 근본 해법은 payment-service 담당 경계라 별도로 다룬다.

## 7. 결론

1. **청크 트랜잭션은 락 보유 시간을 42배 줄인다** — 최장 트랜잭션 중위 3,212 ms → 76 ms. `application.yml`이 `chunk-size: 25`의 근거로 적어 둔 성질이 실측으로 확인됐다.
2. **처리량 대가는 없다** — tick 총 소요가 두 arm에서 겹친다.
3. **커넥션 풀 고갈 회피는 이 경로의 효과가 아니다** — 단일 스레드 경로라 `pending`이 구조적으로 0이다.
4. **상한 동작은 설계대로다** — 10,000건이 정확히 5 tick, tick당 2,000건으로 소진됐다(5분 18초).
5. **관측 공백 두 개를 확정했다** — `seat_held`는 만료 적체를 세지 않는다(PR #487에서 게이지 추가). 만료 이벤트 생성률이 릴레이 상한을 초과해 outbox가 단조 증가하고, 그 지연이 결제 만료 가드를 분 단위로 무력화한다(**#489**).

## 8. 한계·주의

- **`seat_hold_expired_backlog` 게이지는 아직 배포되지 않았다.** CD가 `main` push 트리거라 develop 머지로는 배포되지 않는다. B1 적체 곡선은 SQL 폴링(`drain-b1.csv`)으로 기록했고, **Grafana 렌더 그래프는 다음 릴리스 이후 캡처한다.**
- **Grafana PNG 5장 첨부, 남은 것은 적체 해소 곡선 1장**(§10). 관측 스택은 `127.0.0.1` 바인딩(ADR 0007)이고 이미지 렌더러 플러그인이 설치되어 있지 않아 API로 PNG를 뽑을 수 없다 — SSH 터널로 사람이 캡처해야 한다. 남은 1장은 게이지 배포가 선행 조건이다.
- **`seat_held = 0`은 측정 창 기준이다.** 이 리포트의 모든 `seat_held` 수치는 A1·A2 반복 창(05:15~05:30Z)과 B1 창(05:41~05:55Z)을 범위로 산출했다. 더 넓게 잡으면 04:26~04:33Z에 2,000짜리 펄스가 있는데, 그건 측정 회차가 아니라 시계 함정으로 잘못 시딩된 코호트다(§2.1.1). 캡처 그래프를 볼 때 이 구간을 측정 결과로 읽으면 오독이다.
- **n이 작다**(A1 5, A2 4). 단일 EC2에 앱 9개 + Kafka·MySQL·Redis·관측 스택이 동거하는 구성이라 회차 간 편차가 크다(A1 58.8~379 ms). 배율은 중위값으로 산출했고 원값을 모두 남겼다.
- `confirmSoldById` 블로킹은 직접 측정 불가(§4).
- **측정 중 사고를 냈다.** 04:45~04:58Z, 리포 루트(`~/ticketrush/`)의 구버전 `docker-compose.prod.yml` 사본으로 `up -d seat-service`를 실행해 `depends_on`의 redis·mysql이 재생성됐고, 그 디렉토리 `.env`에 `REDIS_PASSWORD` 키가 없어(#426 이후 추가된 키) Redis가 `--requirepass` 없이 떴다. 비밀번호를 보내는 앱들이 전부 `ERR AUTH ... called without any password configured`로 끊겼고 seat-service는 22회 재시작, `redis_up=0` 알림이 발화했다. 정상 디렉토리 `~/ticketrush/deploy/`에서 재실행해 04:58Z 복구했다(볼륨이 명시적 이름이라 데이터 손실 없음). **이 구간 수치는 리포트에 쓰지 않았다.** 실행 중 스택의 소유 경로는 `docker inspect <c> --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}'`로 확인한다 — 런북 §11.4에 고정했다.

## 9. 증적 파일

| 파일 | 내용 |
|---|---|
| `metadata.txt` | 실행 파라미터·설정 확증·전 회차 원값 — 수치의 SSOT |
| `drain-b1.csv` | B1 적체 해소 곡선 (5초 간격, `hold_expired_at <= UTC_TIMESTAMP()` 카운트 + outbox pending) |
| `hikari-a1.csv` / `hikari-a2.csv` / `hikari-b1.csv` | actuator 1초 폴링 — `pending`/`active`/`idle` |
| `trx-samples-a1.csv` / `trx-samples-b1.csv` | `innodb_trx` 1초 샘플러 — `rows_locked`·`LOCK WAIT`·`data_lock_waits` |
| `timeseries-seat-held-*.json` | **측정 창(A1·A2 반복 / B1)에서 `ticketrush_seat_held` = 0** — §2.1의 근거 |
| `timeseries-outbox-backlog-b1.json` | backlog 5,200 단조 증가 — §6.1 |
| `timeseries-relay-rate-b1.json` | 릴레이 발행률 20.00/s 상한 — §6.1 |
| `timeseries-hikari-{pending,active}-*.json` | Prometheus 15초 스크랩 대조군 (§5의 해상도 한계) |
| `timeseries-node-cpu*.json` | 호스트 CPU (포화 여부 단서) |
| `timeseries-inbox-rate-b1.json` | booking-group 인박스 소화율 |
| `graph-seat-held-semantics.png` | **§2.1.1** — 같은 좌석 2,000건이 미만료일 때 2,000, 만료 적체일 때 0으로 읽히는 대조 (13:20~14:56 KST) |
| `graph-outbox-backlog-b1.png` | **§6.1** — tick마다 2,000 계단 상승 → 피크 5,200(14:48) → 14:53 선형 소진 (14:41~14:57 KST) |
| `graph-relay-rate-b1.png` | **§6.1** — 14:45~14:53 내내 정확히 20/s 천장에 눌린 평평한 선 (14:41~14:57 KST) |
| `graph-hikari-b1.png` | **§5** — `pending` 0에 붙은 직선 / `active` 0↔1 / `idle` 10↔9 (14:41~14:57 KST). **bump가 5개 tick 중 3개만 보인다** — 15초 스크랩이 2~5초짜리 tick을 놓치는 것이고, `hikari-b1.csv`(actuator 1초 폴링)가 그 공백을 메운다 |
| `graph-hikari-a1-a2.png` | **§5** — A1·A2 반복 구간(14:15~14:31 KST)의 같은 3종. 여기서도 `pending`은 창 전체 0이다. **중간의 시리즈 끊김 두 개는 컨테이너 재생성이다** — `up=0` 구간이 05:22:00~05:22:30Z(오버라이드 적용, 프로세스 기동 05:21:37Z)와 05:29:00~05:29:30Z(원복, 기동 05:28:37Z)로 `process_start_time_seconds`에서 확인된다. 따라서 **14:22 끊김이 A1(청크)과 A2(단일)의 경계**다 — 왼쪽이 A1, 오른쪽이 A2. 개별 bump를 특정 tick에 대응시키지는 않는다(15초 해상도로는 신뢰할 수 없다 — 그 대응은 `hikari-a1.csv`·`hikari-a2.csv`가 1초로 갖고 있다) |

## 10. Grafana 캡처 — 현황과 남은 것

관측 스택은 `127.0.0.1` 바인딩(ADR 0007)이고 이미지 렌더러 플러그인이 없어(`grafana cli plugins ls` → no installed plugins) API로 PNG를 뽑을 수 없다. SSH 터널을 열고 Explore에서 수동 캡처한다.

```bash
ssh -i <key>.pem -L 3000:localhost:3000 ubuntu@54.116.243.250
# → http://localhost:3000 (admin/admin) → Explore → datasource Prometheus
```

시간 범위는 **KST**로 입력한다(측정 로그의 UTC + 9시간). 날짜는 전부 2026-07-25다.

### 완료

| 파일 | 쿼리 | 범위 (KST) |
|---|---|---|
| `graph-seat-held-semantics.png` | `ticketrush_seat_held{instance="seat-service:8090"}` | 13:20 ~ 14:56 |
| `graph-outbox-backlog-b1.png` | `ticketrush_outbox_backlog{instance="seat-service:8090"}` | 14:41 ~ 14:57 |
| `graph-relay-rate-b1.png` | `sum(rate(ticketrush_outbox_relay_total{instance="seat-service:8090",result="success"}[1m]))` | 14:41 ~ 14:57 |
| `graph-hikari-b1.png` | 아래 세 쿼리를 A·B·C 행에 하나씩 | 14:41 ~ 14:57 |

**HikariCP 3종은 쿼리 세 개를 한 칸에 넣을 수 없다.** Explore의 `+ Add query`로 A·B·C 행을 만들어 한 행에 하나씩 넣는다(한 칸에 이어 붙이면 PromQL 파싱이 실패해 `No data`가 된다).

```promql
A: hikaricp_connections_pending{instance="seat-service:8090"}
B: hikaricp_connections_active{instance="seat-service:8090"}
C: hikaricp_connections_idle{instance="seat-service:8090"}
```

| `graph-hikari-a1-a2.png` | 위 세 쿼리 그대로, 시간 범위만 변경 | 14:15 ~ 14:31 |

### 남은 것

**적체 해소 곡선(`ticketrush_seat_hold_expired_backlog`)뿐이다.** 게이지가 배포된 뒤에 캡처한다 — CD가 `main` push 트리거라 PR #487 머지(develop)로는 배포되지 않았다. 그때까지는 `drain-b1.csv`(5초 간격 SQL 폴링)가 그 자리를 대신한다. 캡처 시 범위는 B1 창 **14:41 ~ 14:57 KST**를 쓰고, 이 리포트 §6의 tick 표와 대조한다.

**적체 해소 곡선 자체(`ticketrush_seat_hold_expired_backlog`)는 게이지가 배포된 뒤에 캡처한다**(CD는 `main` push 트리거다). 그때까지는 `drain-b1.csv`가 그 자리를 대신한다.
