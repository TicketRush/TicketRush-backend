# 대기열 ON/OFF 대조 1만 VU 회차 — #554

> ⚠️ 작성 중. 수치가 들어갈 자리는 `TBD` 로 비워 두었고, **회차가 끝난 뒤 `arm-stats.py` 출력으로만 채운다.**
> 결론 문장을 먼저 써 두면 그 틀에 맞춰 해석하게 되므로 §0 · §4 · §5 는 실측 전에 쓰지 않는다.

## 0. 한 줄 요약

TBD

---

## 1. 이 회차가 답해야 하는 것

[#549](https://github.com/TicketRush/TicketRush-backend/issues/549) B-2 는 대기열 **ON** 에서 예매 서버 RPS 19.60-21.00 · 예매 p95 최대 42.9ms · 오버셀 0행을 얻었다. 그런데 **"대기열이 없으면 어떻게 되는가" 의 실측이 저장소에 없었다.** 비교 대상이 없으면 그 수치가 성과인지 판별할 수단이 없다.

`queue.enabled` 가 환경변수 하나로 열려 있어 **같은 배포본에서 대조군을 만들 수 있다.** 이 회차의 결론은 절대값이 아니라 **회차 내부의 ON/OFF 차이**이고, 결과는 후속 admit-rate 계단 회차([#555](https://github.com/TicketRush/TicketRush-backend/issues/555))의 입력값이 된다.

### 1.1 이 회차가 답할 수 있는 질문은 하나뿐이다 — 회차 전에 확정했다

부하 모델을 회차 전에 산술로 확정하고 `metadata.txt` 에 커밋했다(커밋 `57930b89`).

```
ARRIVAL_RATE = QUEUE_FLOOD_VUS(10,000) / QUEUE_FLOOD_RAMP(300s) = 33.33 여정/s
```

`waiting-room.js` 의 `journeyDone` 가드로 **VU 1개는 여정을 1회만 탄다.** 그래서 유입은 "1만 동시" 가 아니라 "초당 33.3 도착" 이고, 유지 15분 구간의 도착률은 0 이다.

| | ON arm | OFF arm |
|---|---|---|
| enqueue | 33.3 RPS | 33.3 RPS |
| status(폴링) | **≈400 RPS** — 수요 = `waiting/T` 인데 `T = ceil(waiting/400)` 이라 `waiting ≥ 1,200` 부터 R 에 산식으로 고정된다 | **33.3 RPS** — 첫 폴링에 승급하므로 사용자당 1회 |
| booking | 20 RPS (admit-rate) | 33.3 RPS (도착률) |
| **게이트웨이 총** | **≈453 RPS** | **≈100 RPS** |

이 모델의 ON 총 요청 예측 약 167,000 은 #549 B-2 실측 **167,083** 과 8% 안에서 맞고, 게이트웨이 RPS max 예측 453 도 그 회차 실측 **434.36** 과 4% 오차다.

**따라서 이 회차는 "대기열이 붕괴를 막는다" 를 증명할 수 없다.** OFF arm 이 도달하는 예매 33.3 RPS 는 [#344](https://github.com/TicketRush/TicketRush-backend/issues/344) 붕괴점(booking 단독 258 RPS 에서 호스트 CPU 99.87%)의 **12.9%** 다. 그리고 대기열을 끄면 전체 요청의 88% 인 폴링이 사라져 **OFF arm 이 ON 보다 서버 부하가 4.5배 가볍다.**

증명할 수 있는 것은 **이연(deferral)** 하나다. 램프가 선형이라 두 arm 다 예매 RPS 가 평평하므로 **평탄성은 판별력이 0** 이고, 수준 차이도 1.67배뿐이라 ON 밴드 상한과 겹칠 위험이 있다. 두 arm 을 실제로 가르는 것은 **유입이 끝난 뒤에 무슨 일이 일어나는가** 다.

---

## 2. ON/OFF 대조표

TBD — `arm-stats.py` 출력으로 채운다.

| 축 | ON | OFF | 비고 |
|---|---|---|---|
| DEFERRAL_RATIO (주 판정축) | TBD | TBD | |
| 예매 서버 RPS (판정창 평균) | TBD | TBD | 창이 arm 마다 다르다(§2.1) |
| 예매 서버 p95 / avg | TBD | TBD | |
| `queue_polls_per_user` avg / max | TBD | TBD | |
| `queue_wait_to_admit_seconds` p95 | TBD | TBD | |
| 게이트웨이 총 RPS (공통 창 max) | TBD | TBD | |
| 총 요청 수 | TBD | TBD | |
| 호스트 CPU max / avg (공통 창) | TBD | TBD | 기저 약 7% |
| seat-service RSS peak | TBD | TBD | |
| HikariCP active / pending max | TBD | TBD | |
| Tomcat busy max | TBD | TBD | |
| 최대 동시 커넥션 | TBD | TBD | |
| Redis 메모리 max | TBD | TBD | |
| `queue_status_unavailable` | TBD | TBD | |
| 실효 코호트 (`queue_enqueue_failed`) | TBD | TBD | 모든 비율의 분모 |
| 오버셀 | TBD | TBD | LTQ 3쿼리 |

### 2.1 판정 창이 arm 마다 다르다

| arm | 창 | 근거 |
|---|---|---|
| ON | t+300s ~ t+500s | 램프 종료 ~ 승급 완료(#549 와 동일) |
| OFF | t+30s ~ t+300s | 첫 예매가 t≈+4s 에 나오고 마지막 VU 가 t+310s 에 끝난다 |
| 공통 비교 | t+30s ~ t+300s | 두 arm 이 동일 유입을 받는 유일한 구간. **자원 축은 반드시 이 창으로 비교한다** |

### 2.2 대조표에 넣지 않은 축 — 유령값

`ticketrush_queue_waiting` 과 `ticketrush_queue_poll_interval_seconds` 는 **OFF arm 에서도 ON 과 거의 같은 곡선을 그린다.** `toSnapshot()` 이 `enabled` 와 무관하게 `remainingWaiting(enqueued, threshold)` 를 계산하고 `recordAdmitted()` 가 게이지에 그대로 쓰기 때문이다(`WaitingRoomService.java:202, 235`). 실제로 기다린 사람은 0명인데 대기 인원 곡선이 그려지므로 나란히 두면 오독한다.

`ticketrush_queue_entry_token_*` 은 OFF 에서 전부 0 인데, 통과해서가 아니라 `EntryTokenGatewayFilter.java:91` 이 계측 **전에** 리턴하기 때문이다. 이 0 을 "OFF 가 통과했다" 로 읽으면 계측 부재를 건전성으로 오독한다 — 그래서 **`> 0` 을 '킬 스위치가 안 먹었다' 는 반대 방향 신호로 뒤집어** 무효 기준에 넣었다.

---

## 3. 회차 조건 — arm 사이에서 바꾼 것은 하나뿐

| 항목 | 값 |
|---|---|
| `IMAGE_TAG` | `b0f3da8464498eaf1e595ac2e478167fb3afee27` (두 arm 동일) |
| PR #589 · #591 포함 | **포함** (`git merge-base --is-ancestor` 확인) |
| nginx | `worker_connections 16384` / `worker_rlimit_nofile 65535` |
| 시딩 | `seed_queue_flood.sql` — arm 사이 재시딩 |
| 생성기 | 로컬 Docker, k6 v2.2.0 (두 arm 동일) |
| 유입 | 1만 VU · 램프 5m + 유지 15m (두 arm 동일) |
| **arm 간 변경** | **`QUEUE_ENABLED` 하나** |

`QUEUE_ADMIT_RATE=20` 은 OFF arm 에서도 유지했다 — 승급에는 안 쓰이지만 `threshold → waiting → pollSeconds(T)` 로 이어져 OFF arm 의 첫 폴링 지연을 정하므로, 바꾸면 두 arm 의 `T` 곡선이 달라져 단일 변수 대조가 깨진다.

### 3.1 선행 조건은 이미 충족돼 있었다

이슈가 요구한 `develop → main` 배포는 PR #605 로 끝나 있었고(CD 09:17 UTC 성공), 추가 배포 없이 회차를 시작했다.

### 3.2 회차 전에 커밋한 것

`#549` 가 `dbf2ee52` 로 한 방식을 따랐다 — **결과를 보고 기준을 고치지 않기 위해** 예측 · 판정 기준 · 무효 기준을 회차 **전에** 커밋했다(`57930b89`).

---

## 4. ON arm 실측

TBD

## 5. OFF arm 실측

TBD

---

## 6. 사전 게이트 — G0~G14

런북 §16.4 의 G0~G7 에 더해 이 회차가 G8~G14 를 추가했다. 그중 셋이 회차 해석에 직접 영향을 줬다.

| # | 결과 | 왜 중요한가 |
|---|---|---|
| G8 무부하 기저 CPU | **약 7%** (min 6.63 / avg 7.03 / max 7.57) | 배포본 드리프트 134파일 +5,765줄이 유휴 CPU 를 밀어올리지 않았다 — 회차 CPU 를 드리프트 탓으로 돌릴 수 없다 |
| G9 Kafka concurrency | **미설정** | `.env.prod.example` 에만 있고 실제 `.env` 엔 없다. `seat_lock_contention > 0` 이 정상이 되는 조건이 성립하지 않는다 |
| G11 예매 홉 수 | **1.00** | 예매 1건 = booking-service 요청 1건. `SeatRestClient` 신설이 예매 경로에 영향을 주지 않아 `booking-server-rps` 축의 의미가 #549 와 같다 |

**회차 전에 세운 "예측이 틀릴 수 있는 지점" 3개 중 2개가 게이트에서 제거됐다.** 남은 것은 "폴링 요청당 CPU 비용이 예매의 1/10 이 아니라 1/3 이면 부등호가 뒤집힌다" 하나이고, 그것이 이 회차가 실제로 답할 질문이다.

---

## 7. 완료 조건 대조

이슈 [#554](https://github.com/TicketRush/TicketRush-backend/issues/554) 의 체크박스 9개.

| # | 완료 조건 | 결과 | 증적 |
|---|---|---|---|
| 1 | 회차 전 `develop → main` 배포 완료, `IMAGE_TAG` 확인 | ✅ | PR #605 머지 · CD 09:17 UTC 성공. `metadata.txt` `DEPLOY_PRECONDITION` |
| 2 | 두 arm 이 **같은 배포본**에서 조건표대로 연속 실행, 실제 `IMAGE_TAG` 기록 | TBD | `metadata.txt` `G0_EC2` · `CONTROL_*` |
| 3 | 배포본의 PR #589 · #591 포함 여부 기록 | ✅ | `merge-base --is-ancestor` 로 양쪽 확인. `metadata.txt` `INCLUDES_PR589` / `INCLUDES_PR591` |
| 4 | arm 사이 초기화 확인 (재시딩 · `queue:*` 0건 · outbox 소진 · seat-service 재시작) | TBD | `metadata.txt` `G12_BETWEEN_ARM_RESET` |
| 5 | 예측 · 판정 기준 · 무효 기준을 회차 **전에** 커밋 | ✅ | 커밋 `57930b89` (ON arm 시작 전) |
| 6 | 꺾이는 지점(RPS · VU)과 최초 포화 지표를 수치로 특정 | TBD | `arm-stats.py` `[꺾이는 지점]` — §4 · §5 |
| 7 | 오버셀 발생 여부를 #344 검증 SQL 로 확인 | TBD | LTQ 3쿼리, arm 별로 cleanup 전에 실행 |
| 8 | ON/OFF 대조표가 리포트에 들어감 | TBD | §2 |
| 9 | 증적이 `load-tests/k6/results/` 회차 디렉토리로 남음 | TBD | §11 |

> 조건 6 의 "꺾이는 지점" 은 **미도달도 결과다.** §1.1 의 산술대로 이 부하 모델의 유입 상한이 33.3 RPS 라 임계 교차가 없을 수 있고, 그 경우 `KNEE=N/A` 와 도달한 최대 동작점을 적는다. 회차 전에 정해 둔 분기다(`metadata.txt` `KNEE_IF_NONE`) — 미도달을 무릎으로 바꿔 쓰지 않는다.

---

## 8. 이 회차가 답하지 못하는 것

회차 **전에** `metadata.txt` 에 적은 그대로다.

- **무릎(포화점)의 위치** — `ramping-vus` 는 다이얼이 없어 실효 도착률이 33.3/s 상수인 **단일 동작점 회차**다. 원리상 스윕이 아니다. 무릎을 원하면 전 여정을 `ramping-arrival-rate` 계단으로 돌리는 별도 회차가 필요하다.
- **"대기열이 붕괴를 막는다"** — §1.1. 이 회차가 답하는 것은 이연의 유무뿐이다.
- **"1만 명 동시 대기"** — 대기 인원 최대는 약 4,000명이다. #546 · #549 가 남긴 숙제가 그대로 남는다. 램프를 배수보다 빠르게 하면 #549 B-1 이 되고 그 회차는 CPU 에서 무너졌다 — 이 하드웨어에서 둘은 동시에 만족되지 않는다.
- **arm 순서 효과** — ON→OFF 연속 실행이라 JIT 워밍업 · 페이지 캐시 · MySQL 버퍼풀이 두 번째 arm 에 유리하다. 순서를 뒤집은 대조가 없다.
- **배포본 드리프트의 몫** — ON arm 이 #549 B-2 와 다르게 나와도 그 차이가 코드 134파일 때문인지 다른 조건 때문인지 이 회차 단독으로는 못 가른다.
- **OFF 는 "대기열 미도입" 이 아니다** — OFF arm 도 enqueue 1회 + status 폴링 1회를 친다.
- **`R=400` 은 OFF 에서 의미가 없다** — 두 arm 의 `T` 가 같은 것은 `enqueue` 응답이 같은 산식을 쓰기 때문이지 OFF 에 폴링 제어가 있다는 뜻이 아니다.

### 8.1 시계열을 잇지 않는 범위

- **#549 B-2(`21d2da2d`)와 절대값으로 직접 잇지 않는다.** 배포본이 다르고 그 사이 39커밋 · 프로덕션 코드 134파일이 들어왔다. 참고 수치로만 병기한다.
- nginx `worker_connections` 768 시절 회차(#348 · #403 · #529)와도 잇지 않는다.
- 단일 EC2 2 vCPU 에 앱 8개 + Kafka · MySQL · Redis · 관측 스택 동거(ADR 0006 · ADR 0007). 절대값에 이웃 서비스의 포화가 섞인다.
- **결제 · 티켓 발급은 여정에 없다.** `StubPaymentApprovalClient` 는 `@Profile("!prod")` **와** `@ConditionalOnProperty(payment.pg.stub.enabled=true)` 의 **AND** 조건으로 등록되므로 prod 에서 실행되지 않는다. 예매가 5-6분 뒤 만료되며 outbox 부하가 사실상 2배가 되는 점은 #348 과 동일하다.

---

## 9. 회차 중에 발견한 것

TBD

---

## 10. 후속 작업

TBD

---

## 11. 증적 파일

| 파일 | 내용 |
|---|---|
| `metadata.txt` | 회차 조건 · 사전 게이트 G0~G14 · **회차 전에 커밋한 예측 · 판정 · 무효 기준** · 실측 |
| `report.md` | 이 문서 |
| `k6-summary-on.txt` · `k6-summary-off.txt` | k6 요약 (클라이언트 측정 SSOT) |
| `timeseries-*-on.json` · `timeseries-*-off.json` | Prometheus 시계열 덤프. **보존 15일이 지나면 이것이 유일한 원자료다** |
| `oversell-on.txt` · `oversell-off.txt` | LTQ 코호트 오버셀 검증 3쿼리 출력 (SQL 원문 포함) |
| `grafana-capture-links.md` | Explore 캡처 링크 (쿼리 · UTC 절대 시각이 URL 에 박혀 있다) |
| `graph-*-on.png` · `graph-*-off.png` | Grafana 수동 캡처 |

회차에 쓴 도구는 저장소에 함께 커밋했다.

| 도구 | 용도 |
|---|---|
| `load-test/bench/arm-stats.py` | arm 별 판정 창 집계 · `DEFERRAL_RATIO` · KNEE 판정 (#549 B-2 덤프로 검증) |
| `load-test/bench/grafana-links.py` | 캡처 링크 생성 |
| `load-test/bench/dump-timeseries.py` | 시계열 덤프 (`k6-queue-enqueue-failed` 추가) |
| `load-test/scenarios/waiting-room.js` | `queue_enqueue_failed` 카운터 추가 (status 태그 포함) |
