# #402 입장 검표 스파이크 부하 측정 — 부하 6배(30→180 req/s)에도 검표 p95는 평평했고, 동일 QR 30회 동시 스캔에서 입장 처리는 정확히 1건

## 1. 목적

공연 시작 직전 입장 게이트에 트래픽이 집중되는 상황에서 검표 경로(`POST /api/v1/entries/verify` → `/check-in`)가 어떻게 버티는지를 정량화한다. 개별 핫패스 측정(#344~#347)과 e2e(#348)가 다루지 않은 구간이고, 검표에는 k6 시나리오가 아예 없었다.

이 경로가 검증 가치가 높은 이유는 구조에 있다. `EntryVerifyUseCase`는 요청마다 booking-service로 **동기 REST 왕복**을 한다(`GET /api/v1/internal/booking/{bookingId}`, connect 3s / read 10s, **재시도·서킷브레이커 없음**). #364에서 QR 조회는 로컬화됐지만 입장 검증만은 권위 있는 `bookingStatus` 확인을 위해 동기 호출을 의도적으로 남겼다. 그 왕복이 검표 지연에 얼마를 더하는지가 미측정 상태였다.

동시에 `TicketCheckInProcessor`의 조건부 UPDATE(`WHERE id=? AND ticket_status='UNUSED'`)가 동일 QR 중복 스캔을 정말 1건만 통과시키는지도 부하 하에서 확인된 적이 없다.

## 2. 측정 설계

### 2.1 선결 문제 — 검표 API가 외부에서 도달 불가였다

측정을 붙이려다 발견했다. 게이트웨이의 ticket-service 라우트 predicate가 `Path=/api/v1/ticket/**,/v3/api-docs/ticket` 뿐이라 `/api/v1/entries/**`가 매칭되지 않는다. AWS에서 인터넷에 열린 포트는 게이트웨이 8080 하나뿐이므로(§7.1, ADR 0007) **관리자 앱도 입장 처리를 할 수 없는 상태**였다.

의도가 아니라 결함으로 판단했다 — ticket-service `SecurityConfig`가 이 경로에 `hasRole("ADMIN")`을 걸고, ADMIN은 게이트웨이가 JWT에서 뽑아 `X-User-Role`로 주입해야만 생기는 값이다. `GatewayHeaderFilter`도 `X-Gateway-Token` 일치를 전제한다. 내부 API 미라우팅(#367)과 같은 계열이다.

**실증:** 배포 전 `POST /api/v1/entries/verify` → **404**, 라우트 추가(`cd89e4b`) 배포 후 → **401**. 이 측정은 라우트가 포함된 배포본(`91fa085`)에서 수행했다.

### 2.2 이슈 서사와 실측의 차이 — "verify 단독 vs check-in 포함"으로는 왕복이 분리되지 않는다

이슈 본문은 booking 왕복의 영향을 "verify 단독 vs check-in 포함"으로 나누자고 했다. 그렇게는 분리되지 않는다. `EntryCheckInUseCase`도 **같은 `verifyAndLoad()`를 거쳐 booking을 호출한다.** 둘의 차이는 왕복이 아니라 조건부 UPDATE 트랜잭션이다.

그래서 통제군을 **QR 발급(`GET /api/v1/ticket/bookings/{id}/qr`)** 으로 잡았다. 게이트웨이 1홉 + JWT 처리 + ticket 단건 SELECT까지 verify와 같고 **booking 호출만 없다**(`TicketQrGetUseCase`는 로컬 데이터만 읽는다 — #364). 같은 iteration에서 연속 측정하므로 부하·호스트 상태도 동일하다.

**이 설계가 맞다는 것은 실측이 확인해줬다.** 피크에서 booking 내부조회 RPS(120.0)가 entries RPS(verify + check-in, 120.0)와 **1:1로 일치**한다 — 두 엔드포인트 모두 왕복을 한 번씩 한다.

### 2.3 클라이언트 관점 왕복 지연은 서버에서 잡히지 않는다

`ticket-service/.../global/config/RestClientConfig.java`가 오토컨피그된 `RestClient.Builder` 빈이 아니라 **생 `RestClient.builder()`** 로 만들어 `ObservationRegistry`가 붙지 않는다 → `http_client_requests_seconds`가 아예 없다. 왕복 비용은 §2.2의 차분(상한)과 booking-service 서버 메트릭(하한)으로 협공했다.

### 2.4 서버 측 p95/p99는 산출 불가

`http_server_requests_seconds`가 `_count`/`_sum`/`_max`만 노출한다. 히스토그램 버킷이 없어(`count(..._bucket{...})` = **0 시계열**) `histogram_quantile()`이 빈 결과를 낸다. **퍼센타일의 SSOT는 k6 클라이언트 측정이고**, 서버 축은 평균으로 읽는다. 전 서비스에 걸린 관측성 공백이라 후속 이슈로 분리한다.

### 2.5 이슈의 "Prometheus 스크랩 1분 간격"은 사실과 다르다

`monitoring/prometheus.aws.yml`의 `scrape_interval`은 **15s**다(기존 4건의 `metadata.txt`도 동일). baseline·회복을 각각 5분 이상 유지하라는 요구 자체는 비교 기준선 확보 목적으로 유효하므로 프로파일은 그대로 뒀다(각 6분).

### 2.6 부하 모델 — 도착률 통제

check-in은 티켓을 `UNUSED → USED`로 **비가역** 소모한다. VU 수로 걸면 소요 티켓 수가 응답 지연에 반비례해 변해 시딩 규모를 미리 정할 수 없다. 그래서 기존 관례(`ramping-vus`)를 깨고 `ramping-arrival-rate`를 썼다. 입장 게이트의 실제 부하도 스캐너 수가 아니라 관객 도착률이다.

| 구간 | 도착률 | 유지 | HTTP req/s |
|---|---|---|---|
| baseline | 10 /s | 6m | 30 |
| 스파이크 램프업 | 10→60 /s | 20s | 30→180 |
| 피크 | 60 /s | 3m | **180** |
| 램프다운 | 60→10 /s | 20s | 180→30 |
| 회복 | 10 /s | 6m | 30 |

iteration 1회 = QR 발급 → verify → check-in (요청 3개, 티켓 1장 소모). QR을 iteration마다 새로 발급해 TTL 5분 제약을 구조적으로 없앴고, 그 덕에 §2.2의 통제군이 생겼다.

### 2.7 시드가 만드는 불완전한 도메인 상태

`seed_entry.sql`은 좌석을 건드리지 않는다 — 검표 경로에 seat-service가 없고 `EntryVerifyUseCase`가 booking에서 읽는 것은 `bookingStatus` 뿐이다. 좌석까지 SOLD로 전이시키면 #344/#345 좌석 코호트를 오염시킨다. 그 대가로 시드는 '좌석은 AVAILABLE인데 예매는 CONFIRMED'라는 도메인상 불완전한 조합이 된다. **측정 경로에 영향이 없음을 확인하고 의도적으로 남긴 절충이다.**

## 3. 실측 결과

**회차 유효성** — 아래가 하나라도 0이 아니면 회차를 폐기하기로 했고, 전부 0이었다.

| 지표 | 값 |
|---|---|
| `entry_not_usable` (예매 미확정 409) | 0 / 38,798 |
| `entry_already_used` (티켓 재사용) | 0 / 38,798 |
| `entry_ticket_exhausted` | 0 / 19,399 |
| `dropped_iterations` | **0** |
| `http_req_failed` (정상 차단 409 제외 에러율) | **0.00%** (0 / 58,198) |

**유입 축 (k6, 퍼센타일 SSOT)** — 단위 ms

| | baseline (6m) | 피크 (3m) | 회복 (6m) |
|---|---|---|---|
| HTTP req/s | 29.90 | **168.05** (max 180.03) | 30.00 |
| qr p95 | 46.50 | 28.89 | 27.19 |
| **verify p95** | **21.73** | **21.71** | **22.20** |
| **check-in p95** | **36.78** | **33.65** | **33.57** |
| verify p99 | 57.54 | 41.98 | 42.54 |
| check-in p99 | 195.47 | 52.31 | 51.05 |
| 에러율 | 0 | 0 | 0 |
| 호스트 CPU % | 22.38 | **67.61** (max 73.93) | 21.92 |
| HikariCP pending (ticket) | 0 | 0 | 0 |

**부하가 6배 올라도 검표 p95가 움직이지 않는다.** verify p95는 21.73 → 21.71 → 22.20ms로 사실상 평평하고, check-in p95는 오히려 baseline(36.78)이 피크(33.65)보다 높다. p99도 같은 방향이다(195.47 → 52.31). baseline이 더 나쁜 이유는 §4.2에서 다룬다.

**처리 축 (서버 측 평균)** — 단위 ms

| | baseline | 피크 | 회복 |
|---|---|---|---|
| qr 발급 | 1.45 | 1.24 | 1.40 |
| verify | 4.04 | 4.44 | 4.05 |
| check-in | 10.84 | 12.17 | 10.80 |
| booking 내부조회 | 1.36 | 1.61 | 1.37 |

서버가 본 처리시간도 피크에서 10-18%만 늘었다(verify +0.40ms, check-in +1.33ms, booking +0.25ms). **이 회차에서 검표 경로는 포화되지 않았다.**

**유입 축과 DB의 대조** — k6가 센 `entry_checkin_ok` 19,399건과 DB의 USED 19,399건이 정확히 일치하고, `booking_id` 범위가 `1000001..1019399`로 **빈틈 없이 연속**이다. `exec.scenario.iterationInTest` 기반 인덱싱이 중복도 누락도 없이 동작했다는 직접 증거다.

## 4. booking 동기 왕복의 기여

### 4.1 왕복은 검표 지연의 지배 요인이 아니다

서버 측 차분으로 읽는다.

| | baseline | 피크 | 회복 |
|---|---|---|---|
| **booking 왕복** (verify − qr) | 2.59 | **3.20** | 2.65 |
| **조건부 UPDATE** (check-in − verify) | 6.80 | **7.73** | 6.75 |
| 그중 booking-service 자체 처리 | 1.36 | 1.61 | 1.37 |

**왕복 총비용 약 3ms 중 절반(1.6ms)이 booking-service의 자체 처리이고, 나머지 1.6ms가 컨테이너 네트워크 + 클라이언트 오버헤드다.** 그리고 왕복(3.20ms)보다 **조건부 UPDATE 트랜잭션(7.73ms)이 2.4배 비싸다.**

즉 이슈가 우려한 "동기 왕복 유지의 대가"는 이 규모에서 검표 지연의 지배 요인이 아니다. 단일 EC2 안의 컨테이너 간 통신이라 왕복이 싸다는 점은 감안해야 한다(§7 한계).

### 4.2 클라이언트 p95의 역전 — 앱이 아니라 측정 순서 탓이다

전 구간 클라이언트 p95는 qr(27.07) > verify(22.11)로 **역전**돼 있다. 설계상 verify = qr + 왕복이므로 이대로 차분하면 왕복 비용이 −4.96ms라는 음수가 나온다. 원인은 앱이 아니라 **qr이 iteration의 첫 요청이라 커넥션 수립 비용을 떠안는 것**이다. 근거 셋:

1. **서버 측 평균은 전 구간·전 단계에서 단조롭다** — qr 1.30 < verify 4.27 < check-in 11.65ms. 서버 측정에는 네트워크·TLS가 포함되지 않는다.
2. **부하가 6배 오른 피크에서 qr p95가 46.50 → 28.89로 내려간다.** 포화라면 반대로 올라야 한다. 도착률이 높아 커넥션이 계속 살아 있는 구간에서 값이 낮아진 것이다.
3. **같은 구간에서 verify p95는 21.73 → 21.71로 평평하다** — 커넥션 비용을 지지 않는다.

같은 이유로 §3의 "baseline이 피크보다 나쁘다"도 설명된다. baseline은 도착률이 낮아 iteration 간 간격이 길고, 그래서 매 iteration의 첫 요청이 커넥션을 다시 세운다.

`k6_http_req_blocked_p95`도 같은 방향을 가리키지만, baseline 구간 17개 표본이 전부 같은 값이라 staleness 반복으로 판단해 **방향성 근거로만 쓰고 수치로 인용하지 않는다.**

> **따라서 왕복 비용의 SSOT는 서버 축 차분이다.** 클라이언트 p95는 사용자 체감 지연(네트워크 포함)으로만 읽는다.

## 5. 동일 QR 동시 다중 스캔

VU 30개가 **같은 QR 토큰**으로 동시에 `check-in`을 친다. 3라운드 모두:

| 라운드 (bookingId) | `dup_checkin_ok` | `dup_already_used` | `dup_unexpected` | SQL 검증 |
|---|---|---|---|---|
| 1025000 | **1 / 30** | 29 / 30 | 0 | `ticket_status=USED` 1행, `used_at` 기록 |
| 1024999 | **1 / 30** | 29 / 30 | 0 | 동일 |
| 1024998 | **1 / 30** | 29 / 30 | 0 | 동일 |

권위 있는 판정은 k6가 아니라 SQL이다(§10.2 oversell 검증과 같은 선). **조건부 UPDATE(`WHERE id=? AND ticket_status='UNUSED'`)가 30회 동시 스캔에서 정확히 1건만 통과시켰다.** 나머지 29건은 영향행수 0 → 현재 상태 재조회 → 409 `TICKET_409_002`로 떨어졌고, 이는 설계된 정상 동작이라 에러율에서 분리했다.

## 6. 부수 발견

**6.1 스모크 회차의 CPU 97.91%는 JVM 워밍업이 섞인 값이다.** 배포 직후 돌린 스모크는 같은 피크(60 iter/s)에서 CPU가 97.91%까지 갔는데, 20분 뒤 본 회차에서는 **73.93%**였다. p95도 크게 달랐다(check-in 234.55 → 33.41ms). 배포 직후 측정은 JIT 컴파일·클래스 로딩이 섞이므로 **워밍업 후 재측정이 필요하다**는 것을 수치로 확인했다. 스모크 수치는 캘리브레이션용으로만 쓰고 폐기했다.

**6.2 커넥션 풀은 이 경로에서 압박받지 않는다.** `hikaricp_connections_pending`이 두 서비스 모두 전 구간 0이고 active peak는 ticket 3 / booking 2다(풀 10). check-in의 조건부 UPDATE는 짧은 단일 트랜잭션이고 booking 왕복은 트랜잭션 **밖**에서 일어나므로(`verifyAndLoad`가 트랜잭션 경계 밖), 커넥션을 오래 물지 않는다.

**6.3 `booking_id`를 AUTO_INCREMENT에 맡기면 안 된다.** k6가 `MIN + iterationInTest`로 대상을 고르므로 ID 연속성이 전제다. `INSERT ... SELECT` + `innodb_autoinc_lock_mode=2`에서는 연속이 보장되지 않아 시드가 고정 범위(1000001~)를 직접 지정한다.

**6.4 시딩·측정 준비에서 잡은 결함 3건** — 전부 "조용히 실패하는" 부류라 런북에 근거와 함께 남겼다.
- bcrypt 해시를 `--init-command`로 넘기면 `$2a$10$`의 `$2`·`$10`이 셸 위치 매개변수로 확장돼 잘린다. 시딩은 성공하고 로그인만 401로 죽는다.
- Windows Git Bash에서 `openssl`이 CRLF를 내보내 평문에 `\r`이 섞인다. 파일을 텍스트 모드로 다시 읽으면 `\r`이 `\n`으로 변환돼 눈으로는 차이가 보이지 않는다(`bcrypt.checkpw`로 직접 대조해 확인).
- 시드의 ADMIN 계정 INSERT가 idempotency 때문에 `WHERE NOT EXISTS`로 막혀 있어, 재시딩해도 새 해시가 반영되지 않았다. 해시가 다르면 UPDATE 하도록 고쳤다.

## 7. 결론 · 해석의 한계

**결론**

1. **입장 게이트 스파이크(30 → 180 req/s, 6배)에서 검표 p95는 사실상 변하지 않았다** — verify 21.7ms, check-in 33.6ms. 에러 0, 커넥션 풀 pending 0, 호스트 CPU 67.6%(피크). 이 규모에서 검표 경로는 포화되지 않는다.
2. **동일 QR 30회 동시 스캔에서 입장 처리는 정확히 1건.** 3라운드 재현했고 SQL로 확인했다.
3. **booking 동기 왕복 비용은 약 3.2ms(피크)로, 조건부 UPDATE(7.7ms)의 절반 이하다.** #364가 유지한 동기 호출이 이 규모에서 지연의 지배 요인은 아니다.
4. **검표 API는 게이트웨이 라우팅 누락으로 외부에서 도달 불가였다.** 이번에 함께 고쳤다(§2.1).

**한계**

- **단일 EC2에 앱 9개 + Kafka·MySQL·Redis·관측 스택이 동거한다.** 절대 수치에 포화가 섞인다(§10.5·§11.7과 같은 단서). 특히 booking 왕복이 **같은 호스트의 컨테이너 간 통신**이라 3ms로 싸게 나왔다 — 서비스가 다른 노드로 분리되면 이 값은 커진다.
- **서버 측 p95/p99를 산출할 수 없다**(§2.4). 서버 축은 평균으로만 읽었으므로 서버 관점의 꼬리 지연은 이 회차로 답할 수 없다.
- **피크 60 iter/s는 포화점이 아니라 캘리브레이션 결과다.** 스모크에서 CPU가 97.91%까지 갔던 것을 근거로 정했는데, 그 값에 워밍업이 섞여 있었다(§6.1). 워밍업 후 기준으로는 더 높은 도착률까지 여유가 있을 가능성이 크다. **이 회차는 "무너지는 지점"을 찾은 것이 아니라 "게이트 스파이크에서 버티는지"를 확인한 것이다.**
- **booking-service 지연/다운 주입은 하지 않았다.** 이슈의 (선택) 항목이지만 "왕복이 끊겼을 때"는 다른 질문이고, 답은 코드로 이미 확정적이다 — `BookingRestClient`에 재시도·서킷브레이커가 없고 read-timeout이 10s라 booking이 느려지면 ticket-service 톰캣 스레드가 요청당 최대 10초 묶이고 곧 503 폭풍이 된다. 별도 측정 창이 또 필요해 후속 이슈로 분리한다. 이번 회차에서 확인한 것은 **정상 부하에서 booking 비성공 응답이 0건**이라는 사실까지다.
- **시드가 도메인상 불완전한 상태를 만든다**(§2.7). 측정 경로에는 영향이 없지만, 이 데이터로 좌석·예매 관련 다른 측정을 하면 안 된다.

## 8. 증적 파일

| 파일 | 내용 |
|---|---|
| `metadata.txt` | 수치의 SSOT. 설정·프로파일·측정 창·전 지표 |
| `k6-summary.txt` | 본 회차 k6 요약 원문(임계값 판정 포함) |
| `k6-summary-smoke.txt` | 스모크 회차 원문(캘리브레이션용, 수치 폐기) |
| `k6-summary-dup.txt` | 중복 스캔 3라운드 + 라운드별 SQL 검증 출력 |
| `timeseries-qr-p95.json` / `verify-p95` / `checkin-p95` / `checkin-p99` | k6 클라이언트 지연 |
| `timeseries-k6-rps.json` / `k6-req-failed.json` | 유입량·에러율 |
| `timeseries-entries-rps.json` | ticket-service 엔드포인트별 RPS |
| `timeseries-booking-internal-rps.json` / `booking-internal-avg-ms.json` | booking 내부조회 호출량·평균 지연 |
| `timeseries-ticket-server-avg-ms.json` | 서버 측 엔드포인트별 평균 지연(§4.1의 원천) |
| `timeseries-node-cpu.json` | 호스트 CPU |
| `timeseries-hikari-pending.json` / `hikari-active.json` | 커넥션 풀 |
| `graph-*.png` (4장) | Grafana Explore 수동 캡처 — 내용은 §9 |
| `grafana-capture-links.md` | 위 캡처를 재현하는 Explore URL(쿼리·시간범위 포함) |

## 9. Grafana 캡처

렌더러 플러그인이 없어 Explore 수동 캡처다(§11.6과 동일). **4장 모두 완료.** 시간범위는 전부 `2026-07-26 18:56:00 ~ 19:12:00 KST`(= 측정 창 09:56:01-10:11:43 UTC)로 통일했다. 쿼리가 박힌 Explore 링크는 `grafana-capture-links.md`에 있다.

| 파일 | PromQL | 그림이 보여주는 것 |
|---|---|---|
| `graph-latency-p95.png` | `1000*k6_entry_qr_duration_p95` / `..._verify_...` / `..._checkin_...` | **피크(19:02-19:05)에도 세 선이 평평하다.** 부하 6배에 p95가 반응하지 않는다는 §3의 결론이 그림으로 보인다. 초반 18:56-18:57의 봉우리는 qr(녹색)만의 것으로, §4.2의 커넥션 수립 비용이다 |
| `graph-rps-cpu.png` | `sum(rate(k6_http_reqs_total[1m]))` + 호스트 CPU | RPS 30 → **180 고원** → 30, CPU 22% → **73%** → 22%. baseline·스파이크·회복 3구간이 대칭으로 잡혔다 |
| `graph-server-avg.png` | 서버 측 uri별 평균 지연 (ticket-service) | check-in(11-12.5ms) > verify(4-4.6ms) > qr(1.3-1.6ms)의 **세 띠가 분리된 채 피크에서도 순서가 유지된다.** §4.2가 "클라이언트 p95 역전은 앱이 아니다"라고 판정한 근거 1이 이 그림이다 |
| `graph-booking-internal.png` | booking 내부조회 RPS + 평균 지연 | 내부조회 RPS가 20 → **120** → 20. entries RPS(verify+check-in) 120과 1:1로 일치해 §2.2의 통제군 설계가 맞았음을 보여준다 |

> `graph-booking-internal.png`의 평균 지연(노란선)은 RPS와 축을 공유해 바닥에 붙어 보인다. 실제 값은 1.36-1.61ms이며 수치는 §4.1 표와 `timeseries-booking-internal-avg-ms.json`을 본다.
