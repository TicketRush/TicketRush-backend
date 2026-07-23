# #346 이벤트 발행 유실 방지 측정 — AFTER_COMMIT vs 트랜잭셔널 Outbox (장애 주입)

2026-07-23, EC2 배포본(54.116.243.250) 대상. 예매 생성 부하 중 Kafka 브로커를 180초 강제 정지해,
`kafka`(AFTER_COMMIT) 모드의 발행 유실과 `outbox` 모드의 유실 0건을 정량 비교했다.

> **전달 보장 전제** (docs/kafka-event-guide.md §4.1): outbox는 **at-least-once**다 — exactly-once가 아니다.
> relay 재시도로 **중복 발행은 허용**되며, 중복 차단은 소비 측 Inbox 패턴(#347)의 몫이다.
> 따라서 이 측정의 검증 대상은 "유실 0건"이고, 중복은 유실과 분리해 집계한다.

## 1. 측정 조건

| 항목 | 값 |
|---|---|
| 토폴로지 | k6 로컬(docker compose) → SSH 터널(8080) → EC2 gateway (ADR 0004) |
| 워크로드 | `load-test/scenarios/booking-create.js` — POST /api/v1/booking, 15rps 캡, VUS 15, 정상≥5분 → 장애 180초 → 복구≥5분 |
| 시딩 | 공연 1개(PERF_ID=3) × 26,000석 (seat_id 121–26120), `seed_load.sql` |
| 장애 주입 | `load-test/chaos/broker-outage.sh` — `docker stop ticketrush-kafka` → 180s → start |
| 180초 근거 | 프로듀서 기본 `delivery.timeout.ms=120s`(KafkaConfig 오버라이드 없음) — 120초 안에 브로커가 복귀하면 버퍼된 send가 성공해 유실이 재현되지 않는다 |
| 유실 집계 | ground truth = DB. 기대(구간 내 booking 생성 수, 커밋 1건=BookingCreatedEvent 1건) vs 실수신(seat-group `inbox`의 BookingCreatedEvent 수). `load-test/chaos/verify-loss.sql` |
| 중복 집계 | `increase(ticketrush_kafka_inbox_total{result="duplicate",consumer_group="seat-group"})` — inbox unique 제약으로 테이블에는 첫 1건만 남으므로 메트릭 델타로 센다 |

### 측정 당시 `app.event-publisher.type` (완료 조건 ④)

컨테이너 env 오버라이드 없음 → `application.yml` 기본값. Phase B만 booking-service에 override 적용.

| 서비스 | Phase A | Phase B |
|---|---|---|
| **booking-service** | `kafka` (AFTER_COMMIT) | **`outbox`** (`booking-outbox.override.yml` env 오버라이드, 코드 미변경) |
| seat-service | `outbox` | `outbox` |
| payment-service / ticket-service / 기타 | `kafka` | `kafka` |

## 2. Phase A — kafka(AFTER_COMMIT) 모드: 유실 재현

- 구간: 12:04:47 ~ 12:19:11 UTC (부하 14분, k6 10,320 iterations / 12.3rps / 실패 0.42%)
- 장애: 12:11:05 ~ 12:14:06 UTC (브로커 181초 정지)

| 항목 | 값 |
|---|---|
| 기대 이벤트(구간 내 booking 커밋) | **10,304** |
| 실수신(seat-group inbox) | **9,790** |
| **유실** | **514건 (5.0%)** |
| 중복 수신 | 0건 |
| DLT 유입 | 0건 |

관측 포인트 (`phase-a-timeline.txt`):
- 장애 전: booking과 inbox가 완전 정합(1292=1292, …, 4744=4744).
- 장애 중: **booking 커밋은 계속 성공(201)**하는데 inbox는 5,414에서 동결 — "커밋됐으나 send 실패"의 유실 창이 그대로 보인다. 단, 프로듀서 버퍼가 차면 send가 블로킹되어 처리량이 급감했다(911/min → 10/min). k6 실패 44건(0.42%)도 이 구간의 여파다.
- 복구 후: `delivery.timeout.ms`(120s)를 넘기지 않은 버퍼 send는 지연 전달됐고, **초과분 514건은 영구 유실**됐다. 유실분은 재시도 주체가 없다 — error 로그만 남는다(fire-and-forget).

## 3. Phase B — outbox 모드: 유실 0건 검증

- 구간: 12:45:29 ~ 13:11:48 UTC (부하 14분 + backlog 소진 관측, k6 12,576 iterations / 15.0rps / 실패 0.00%)
- 장애: 12:51:47 ~ 12:54:48 UTC (브로커 181초 정지 — Phase A와 동일 조건)
- 모드 전환: `booking-outbox.override.yml` env 오버라이드(`APP_EVENT_PUBLISHER_TYPE=outbox`)로만 — 코드·기본 설정 미변경. backlog Gauge 노출로 활성 검증.

| 항목 | 값 |
|---|---|
| 기대 이벤트(구간 내 booking 커밋) | **12,576** |
| 실수신(seat-group inbox) | **12,576** |
| **유실** | **0건** |
| 중복 발행(수신 측 감지) | **1건** — Inbox가 차단(DB에는 1행만 존재) |
| outbox 상태(BookingCreatedEvent) | SENT 12,576 / FAILED 0 / **DEAD 0** |
| DLT 유입 | 0건 |

관측 포인트 (`phase-b-timeline.txt`):
- 장애 중에도 **HTTP 실패 0, 처리량 저하 0** (15rps 유지). 발행이 로컬 INSERT라 요청 경로가 브로커와 완전히 분리된다 — Phase A에서는 send 블로킹으로 처리량이 911→10/min까지 떨어졌다.
- 장애 구간 outbox `PENDING` 적체: 431 → 8,900대(피크). 브로커 복구 후 relay(5초 주기, 배치 100)가 분당 최대 1,200건으로 소진, **13:11:48 UTC backlog 0**.
- 커밋된 이벤트는 전량 outbox에 남아 있으므로 **재시도 주체가 명확**하다. at-least-once의 대가인 중복 1건은 소비 측 Inbox 멱등키(`uk_inbox_group_event`)가 차단했다.

## 4. 결과 요약

| | `kafka` (AFTER_COMMIT) | `outbox` (트랜잭셔널 Outbox) |
|---|---|---|
| 발행 유실 | **514건 / 10,304건 (5.0%)** | **0건 / 12,576건** |
| 중복 발행 | 0건 | 1건 → **Inbox가 전량 차단** |
| 장애 중 API 영향 | 실패 44건, 처리량 911→10/min 급락(send 블로킹) | **영향 없음** (15rps·실패 0 유지) |
| 복구 수단 | 없음(error 로그만) | relay 자동 재시도, backlog 소진 관측 가능 |

> 포트폴리오 문구: **"181초 브로커 장애 주입 하에서 AFTER_COMMIT 발행 유실 514건(5.0%) → 트랜잭셔널 Outbox 전환으로 유실 0건(at-least-once). 발생한 중복 1건은 소비 측 Inbox가 전량 차단."**
> outbox가 보장하는 것은 유실 0(at-least-once)이지 exactly-once가 아니며, effectively-once는 Inbox(#347)와의 조합으로 달성된다.

## 5. 그래프 (완료 조건 ⑤)

Grafana image renderer 미설치로 패널 이미지는 수동 캡처가 필요하다. SSH 터널(`-L 3000`) 후
http://localhost:3000 Explore에서 아래 쿼리를 측정 시간 범위로 캡처해 이 디렉토리에 저장:

| 파일명(제안) | PromQL | 봐야 할 것 |
|---|---|---|
| `graph-outbox-backlog.png` | `ticketrush_outbox_backlog` | Phase B 장애 구간 적체 상승 → 복구 후 0 소진 |
| `graph-inbox-rate.png` | `rate(ticketrush_kafka_inbox_total{consumer_group="seat-group"}[1m])` | Phase A 장애 구간 수신 단절 vs Phase B 복구 후 따라잡기 |
| `graph-dlt.png` | `increase(ticketrush_kafka_dlt_total[5m])` | DLT 유입 여부(소비 실패) |
| `graph-k6-rps.png` | `rate(k6_http_reqs_total[1m])` | 부하 형상(장애 중에도 201 지속 = 유실 창의 증거) |

원시 시계열(json)은 같은 디렉토리 `timeseries-*.json`으로 저장했다.

## 6. 재현 방법

`docs/load-test-guide.md` §8 런북 참조.
