# #347 Inbox 멱등성 측정 — 중복 차단율·이중 발급 0건

> 측정일 2026-07-24 (KST) · 대상 EC2 배포본(`a5eb970`) · 런북 [docs/load-test-guide.md §9](../../../../docs/load-test-guide.md)

## 1. 목적

Kafka at-least-once 환경에서 동일 이벤트가 대량 재전달돼도 소비 측 Inbox(`uk_inbox_group_event`)가 전량 차단하는지 정량화한다. 짝 이슈 #346이 Outbox로 **유실 0건**(중복 발행 허용)을 증명했고, 그 중복을 실제로 걸러내 실질 effectively-once를 완성하는 것이 이 측정의 Inbox다.

## 2. 측정 설계

- **재전달 유발**: `kafka-consumer-groups --reset-offsets --to-earliest` 루프(`load-test/chaos/inbox-redeliver.sh`). 그룹의 커밋 오프셋만 되돌리므로 토픽 누적 이벤트가 **원본 eventId 그대로** 재소비된다 — 브로커 장애 없이 at-least-once 재전달을 대량·결정적으로 재현. (#346의 브로커 정지 방식은 실측 중복 1건뿐이라 기각)
- **주 대상**: `seat-group` × `booking-created-topic` — #346 측정이 적재한 **22,366건**이 사이클당 재전달 볼륨. 차단 로직·계측(`kafka.inbox`)은 `InboxService.runIfFirst` 한 곳이라 consumer_group과 무관하게 동일 코드의 측정이다.
- **보조(ticket-group)**: `payment-confirmed-topic` 잔존 이벤트 0건(전 파티션 offset 0)이라 부하 측정 불가. 티켓 경로(결제확정 N회 재전달 → 티켓 정확히 1건)는 실 MySQL 동시성 테스트가 검증한다(§5).

## 3. 실측 결과

| 항목 | 값 |
|---|---|
| 측정 구간 (UTC) | 07:37:50 ~ 07:44:17 (**6분 27초** — 5분 유지 조건 충족) |
| reset 사이클 수 | 6 (사이클당 22,366건 전량 재전달·소진) |
| 재전달 총량 (ground truth) | **134,196건** (= 6 × 22,366, 전 사이클 lag 0 확인) |
| 중복 차단 (PromQL 증분) | 123,244건 (카운터 리셋 보정 특성상 ~8% 과소집계 — §6) |
| 신규 처리(processed) | 0건 (시리즈 미생성 — 측정 창 내 신규 이벤트 없음) |
| **중복 차단율** | **100%** (= duplicate/(duplicate+processed), ground truth 134,196/134,196) |
| 재처리(멱등 깨짐) | **0건** — inbox seat-group 22,366행 측정 전/후 불변 |
| 티켓 이중 발급 | **0행** (`verify-inbox.sql` ①) |

핵심 정합: 재전달 134,196건 전량이 `runIfFirst`의 exists fast-path에서 차단됐다. **inbox 행 수가 1행도 늘지 않았다는 것이 곧 "비즈니스 재실행 0건"의 DB 증거**다(늘었다면 재처리 발생).

## 4. 타임라인 (5분 유지 근거)

`redeliver-log.txt` 원문 기준 — 사이클 간 공백은 seat-service 재시작(~25-35초)뿐이고, duplicate 소비는 구간 내내 반복된다:

| 사이클 | reset (UTC) | 22,366건 소진 |
|---|---|---|
| 1 | 07:37:52 | ~58s |
| 2 | 07:39:00 | ~55s |
| 3 | 07:39:55 | ~55s |
| 4 | 07:40:50 | ~72s |
| 5 | 07:42:02 | ~84s |
| 6 | 07:43:26 | 종료 후 07:44:17 소진 |

곡선 재현 PromQL (원시 데이터는 `timeseries-inbox-duplicate-rate.json`, 15s step — 피크 ~480/s):

```promql
sum(rate(ticketrush_kafka_inbox_total{result="duplicate",consumer_group="seat-group"}[1m]))
# 차단율
sum(increase(ticketrush_kafka_inbox_total{result="duplicate",consumer_group="seat-group"}[400s]))
/ sum(increase(ticketrush_kafka_inbox_total{consumer_group="seat-group"}[400s]))
```

## 5. 동시성 테스트 (티켓 이중 발급 0건)

EC2에는 결제 흐름이 없어 ticket 테이블이 0행이므로, 티켓 경로는 `ticket-service`의 `TicketIssueConcurrencyTest`가 검증한다 — 실물 `InboxService`+`TicketIssueUseCase`를 실 MySQL(Testcontainers, prod 동일 mysql:8.0)에 물리고, 동일 결제확정 이벤트(같은 eventId/bookingId)를 10스레드 CountDownLatch/CyclicBarrier 동시 재주입:

- 성공(`runIfFirst==true`) 정확히 1, 나머지 9는 전부 차단(inbox fast-path 또는 티켓 `booking_id` unique 충돌·롤백)
- **티켓 정확히 1건 — 이중 발급 0건**, inbox 1행
- 순차 재전달 5회 전부 fast-path 차단, 메트릭으로 차단율 산식 검증 — 통과 (`./gradlew :ticket-service:test`)

## 6. 해석·한계

- **차단율 100%**: 재전달 이벤트는 inbox에 기록이 있으므로 전량 fast-path(`existsBy` 1쿼리)에서 걸러진다. 처리량 ~380-480/s로 22,366건을 1분 내 소진 — 차단 비용이 매우 낮다.
- **PromQL 증분(123,244)과 ground truth(134,196)의 차이**: 사이클마다 seat-service를 재시작하므로 카운터가 0으로 리셋되는데, 마지막 스크랩(15s 간격)과 컨테이너 정지 사이의 증분은 Prometheus 리셋 보정이 알 수 없어 누락된다(~8% 과소). **차단율 산출에는 영향 없다** — 분자·분모가 같은 창의 같은 시리즈라 비율은 보존된다. 절대량의 SSOT는 오프셋 기반 ground truth로 잡는다.
- **processed 0의 의미**: 측정 창에 신규 이벤트가 없어 차단율이 정의상 100%로 나온다. k6 병행(런북 §9.2 권장)으로 신규 트래픽을 섞으면 100% 미만의 실측 혼합비가 나오지만, "재전달분 차단율"은 그와 무관하게 재처리 0건(inbox 불변)으로 증명된다.
- **#346과의 연결**: #346 Phase B에서 자연 발생한 중복 1건도 Inbox가 차단했다(DB 1행). 이번 측정은 그 방어선이 134,196건 규모에서도 동일하게 동작함을 보인다 — Outbox(유실 0) + Inbox(중복 차단) = 실질 effectively-once.

## 7. 증적

| 파일 | 내용 |
|---|---|
| `metadata.txt` | 측정 파라미터·구간·원시 수치 |
| `redeliver-log.txt` | 사이클·lag 타임라인 (inbox-redeliver.sh 출력) |
| `timeseries-inbox-duplicate-rate.json` | duplicate 1m rate 곡선 (query_range, 15s step) |
| `timeseries-inbox-duplicate-counter.json` | duplicate 원시 카운터 (리셋 포함) |
| `graph-inbox-duplicate-rate.png` | Grafana Explore 캡처 — duplicate 1m rate 곡선 (피크 ~481/s, 구간 내 연속) |
| `graph-inbox-duplicate-counter.png` | Grafana Explore 캡처 — 원시 카운터 톱니 6개 (사이클당 0→22,366) |

> Grafana 캡처 구간은 16:35-16:46 KST(= 07:35-07:46 UTC). 카운터 캡처의 중간 톱니 일부가 ~21K/~11.7K에서 잘려 보이는 것은 재시작 직전 스크랩이 피크를 놓친 것으로, §6의 PromQL ~8% 과소집계를 시각적으로 보여준다.
