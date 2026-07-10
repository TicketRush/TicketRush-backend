# 부하 테스트 실행 가이드

seat/booking/ticket 핫패스의 처리량·latency를 k6로 측정하고, 기존 Prometheus + Grafana 스택에서 관측한다.

## 1. 개요

```
k6 (컨테이너, loadtest profile) --(remote-write)--> Prometheus :9090 --(scrape)--> Grafana :3000
                                                          ↑
                        앱 8개(호스트) /actuator/prometheus (기존 scrape)
```

- k6는 docker-compose의 `loadtest` profile 컨테이너로 실행한다(호스트에 k6 설치 불필요, 평소 `up`엔 안 뜸).
- k6 결과는 **내장 remote-write 출력**(`experimental-prometheus-rw`, compose의 `K6_OUT`로 기본 적용)으로 Prometheus에 push한다. 커스텀 xk6 빌드나 InfluxDB는 쓰지 않는다.
- 부하 생성기와 대상이 같은 머신을 공유하므로 리소스가 경쟁한다. 신뢰 가능한 수치가 필요하면 대상을 원격(AWS 배포본)에 두고 분리 실행한다(§7).
- k6 시계열(`k6_*`)과 앱 계측(`ticketrush_*`)이 같은 Prometheus에 모여, 하나의 Grafana에서 교차 관측된다.

## 2. 사전 조건

### 2.1 도구
- Docker / Docker Compose (k6는 `loadtest` profile 컨테이너로 실행 — 로컬 k6 설치 불필요)
- MySQL 클라이언트 (`mysql`), MySQL **8.0+** (시딩 스크립트가 재귀 CTE·윈도우 함수 사용)

### 2.2 모니터링 스택
```bash
docker compose up -d prometheus grafana
```
`docker-compose.yml`의 prometheus에 `--web.enable-remote-write-receiver`가 포함되어 있어 `/api/v1/write` 수신 엔드포인트가 열린다. 확인:
```bash
curl -s -o /dev/null -w "%{http_code}\n" -XPOST http://localhost:9090/api/v1/write
# 405/400 등 응답이 오면 receiver 활성(연결 거부가 아니면 OK)
```

### 2.3 앱 스택
MySQL(3306)과 측정 대상 서비스를 호스트에서 기동한다. 최소 구성:
- 게이트웨이 8080, auth 8082(로그인), user 8081, booking 8084, seat 8086
- DB 계정은 `.env.local`의 `MYSQL_USERNAME`/`MYSQL_PASSWORD`

> `booking-create`는 `POST /api/v1/auth/login`으로 인증한다. 계정은 3장에서 함께 시딩된다.
> DevToken(`/api/v1/dev/auth/token`)은 로컬 프론트엔드 테스트 전용이며 부하 테스트는 쓰지 않는다.

## 3. 대량 데이터 시딩

`load-test/seed/seed_load.sql` 상단 `@vars`로 규모를 정한다:

| 변수 | 의미 | 기본 |
|------|------|------|
| `@perf_count` | 공연 수 | 10 |
| `@rows_per` | 공연당 좌석 행(≤26) | 20 |
| `@cols_per` | 공연당 좌석 열 | 30 |
| `@booking_pct` | PENDING 예매+HOLD 선점 비율(%) | 0 |

총 좌석 = `perf_count × rows_per × cols_per`. 수십만 건은 `perf_count`/`cols_per`를 키운다.

```bash
# 시딩 (idempotent: 마커 LOADTEST 기준, 재실행은 cleanup 먼저)
mysql -h 127.0.0.1 -u "$MYSQL_USERNAME" -p"$MYSQL_PASSWORD" ticket_rush < load-test/seed/seed_load.sql

# 리셋(규모 변경/정리) → 반드시 cleanup 먼저, 그다음 재시딩
mysql -h 127.0.0.1 -u "$MYSQL_USERNAME" -p"$MYSQL_PASSWORD" ticket_rush < load-test/seed/cleanup_load.sql
```

시딩 끝에 검증 카운트(load_users/performances/seats/bookings)가 출력된다. `@booking_pct>0`이면 이슈의 "PENDING 예매 대량 생성(만료/조회/인덱스 측정)" 요건을 만족한다.

시드는 공연·좌석과 함께 **부하테스트 전용 계정 1개**(`loadtest@ticketrush.local`)를 만든다. `booking-create`가 이 계정으로 로그인한다.

> 비밀번호 **평문은 커밋하지 않는다.** `seed_load.sql`의 `@load_pw_hash`(bcrypt cost 10)와 쌍을 이루며, 평문은 팀 내부에서만 공유하고 k6 실행 시 `-e LOAD_USER_PASSWORD=...`로 넘긴다.

## 4. k6 실행

k6는 `loadtest` profile 컨테이너로 실행한다. remote-write 관련 설정(`K6_OUT`, `K6_PROMETHEUS_RW_SERVER_URL`, `K6_PROMETHEUS_RW_TREND_STATS`)과 `BASE_URL`(→ `host.docker.internal:8080`)은 compose에 정의돼 있어 매번 `-o`/URL을 줄 필요가 없다.

스크립트는 `./load-test`가 컨테이너 안 `/scripts`로 마운트된다. 시나리오 파라미터는 `-e KEY=VALUE`로 덮는다 (`BASE_URL, PERF_ID, LOAD_USER_EMAIL, LOAD_USER_PASSWORD, SEAT_ID_MIN, SEAT_ID_MAX, VUS, RAMP, STEADY`).

```bash
# (b) 좌석 조회 — 인증 불필요, 먼저 파이프라인 검증용
docker compose run --rm k6 run \
  -e PERF_ID=1 -e VUS=50 \
  /scripts/scenarios/seat-layouts.js

# (a) 예매 생성 — setup()에서 1회 로그인 후 Bearer 호출
docker compose run --rm k6 run \
  -e PERF_ID=1 -e LOAD_USER_PASSWORD='<평문>' \
  -e SEAT_ID_MIN=1 -e SEAT_ID_MAX=6000 \
  /scripts/scenarios/booking-create.js
```

> 대상 앱(게이트웨이·auth·seat 등)은 호스트에서 기동 중이어야 하고, prometheus/grafana 스택도 먼저 떠 있어야 한다(2.2). 컨테이너는 이들에 `host.docker.internal`로 접근한다.

## 5. Grafana 관측

1. http://localhost:3000 (기본 `admin`/`admin`) 접속
2. Explore → datasource Prometheus(uid `prometheus`)
3. 처리량·latency:
   - `rate(k6_http_reqs_total[1m])` — 초당 요청 수
   - `k6_http_req_duration` (p95 등) — 응답 지연
4. 앱 계측 교차 확인:
   - `rate(ticketrush_booking_created_total[1m])` — 예매 생성 소화율
   - `ticketrush_seat_lock_contention_total` — 좌석 락 경합
   - `ticketrush_outbox_backlog`, `ticketrush_kafka_inbox_total` — 파이프라인 적체/멱등
5. (선택) 반복 관측이 필요해지면 k6용 대시보드 JSON을 `monitoring/grafana/dashboards/`에 추가하면 provisioning이 자동 로드한다.

## 6. 트러블슈팅 / 리스크

- **좌석 고갈**: 예매 부하가 좌석을 HOLD로 소모해 지속 부하 시 `SEAT_NOT_AVAILABLE`이 늘 수 있다. 대응 — `@cols_per`를 키워 AVAILABLE 풀을 크게, `SEAT_ID_MAX`를 넓게 분산, 실행 사이 cleanup+재시드 또는 `UPDATE seat SET seat_status='AVAILABLE', booking_number=NULL, hold_expired_at=NULL WHERE ...`로 리셋. 순수 처리량은 좌석 조회 시나리오로, 예매는 짧은 스파이크로 본다.
- **Grafana에 p95가 없음**: compose의 `K6_PROMETHEUS_RW_TREND_STATS` 설정 확인.
- **remote-write 연결 실패**: k6 컨테이너와 prometheus가 같은 `ticketrush-net`에 있는지, prometheus에 receiver 플래그가 적용됐는지(`docker inspect prometheus`의 command) 확인.
- **앱 접근 실패**: 대상 서비스가 호스트에서 기동 중인지, k6 컨테이너의 `BASE_URL`이 `host.docker.internal:8080`인지 확인(Linux는 compose의 `extra_hosts`로 매핑됨).
- **공유/운영 DB 시딩 금지**: 시딩·cleanup은 마커 범위만 건드리지만 반드시 로컬 전용 DB에서만 실행한다.

## 7. 원격 실행 — k6 로컬 + AWS 배포본

k6는 **로컬에서 그대로 실행**하고, 대상 앱은 **AWS에 배포된 인스턴스**를 타격해 부하 생성기와 대상을 분리한다. 기존 `loadtest` 프로파일·시나리오·시딩은 그대로 쓰고 `BASE_URL`만 AWS 엔드포인트로 덮으면 된다.

> 이 토폴로지를 택한 근거(전부 로컬·Oracle 무료·k6=원격+앱=로컬 등 대안 비교, 왜 반대 방향이 아닌지)는 [ADR 0004](adr/0004-load-test-execution-topology.md)가 SSOT다. 여기서는 실행 방법만 다룬다.

```
k6 (로컬 loadtest 컨테이너) ──HTTP────────────> AWS 배포 앱 (공인 게이트웨이 :8080)
        │                                              ▲
        └─ remote-write ─> Prometheus :9090 ──scrape───┘   (k6_* + ticketrush_* 한 TSDB)
                                  │
                                  └──> Grafana :3000
```

- 로컬 k6의 remote-write 대상(`K6_PROMETHEUS_RW_SERVER_URL`)을 **앱 지표를 스크랩하는 Prometheus**로 돌리면, k6 클라이언트 시계열(`k6_*`)과 앱 계측(`ticketrush_*`)이 **같은 TSDB에 모인다.** 대시보드 JSON은 datasource uid `prometheus`를 참조하므로 수정할 것이 없다.
- 덕분에 `k6_http_req_duration`(생성기가 본 응답시간)과 `http_server_requests_seconds`(앱이 본 처리시간)를 한 화면에서 겹쳐 볼 수 있다. **둘의 차이가 곧 네트워크 왕복**이라, 가정용 회선이 병목인지 앱이 병목인지 갈린다.
- Prometheus를 로컬에 둘 수는 없다. pull 방식이라 앱 7개(8081~8087)의 포트를 인터넷에 열어야 하기 때문이다([ADR 0004](adr/0004-load-test-execution-topology.md)가 대안 3을 기각한 이유와 같다).

> 아래에서 `<PROM_HOST>`는 앱 지표를 스크랩하는 Prometheus가 떠 있는 호스트다. 관측 스택을 어디에 배치하는지는 AWS 배포 토폴로지를 정하는 ADR에서 다룬다.

### 7.1 사전 조건 (AWS)
- 부하 대상 앱을 AWS에 배포하고 게이트웨이 공인 엔드포인트(`https://<aws-gateway>`)를 확보한다.
- 관측 스택이 기동 중이고, Prometheus 9090이 **본인 공인 IP에 대해서만** 열려 있어야 한다. remote-write 수신에는 인증이 없다.
- `seat-layouts`(read)는 **인증 불필요** → 배포 프로파일과 무관하게 바로 가능.
- `booking-create`(write)도 정상 로그인(`/api/v1/auth/login`)을 쓰므로 배포 프로파일과 무관하다. 다만 AWS에서 실제로 돌릴지는 배포본의 컨테이너·리소스 구성 결정을 따른다(#378).
- 시딩은 3장과 동일하게 **AWS의 부하테스트용 DB**에 대해 수행한다(운영 DB 금지). 부하테스트 계정도 함께 시딩된다.

### 7.2 k6 실행 (로컬)
4장과 동일하되 `BASE_URL`과 `K6_PROMETHEUS_RW_SERVER_URL`을 덮는다.

> **`-e`의 위치가 서비스명 앞뒤로 다르다.**
> `docker compose run` **앞**의 `-e`는 **컨테이너 환경변수**(k6 바이너리가 읽는 `K6_*` 설정)이고,
> `k6 run` **뒤**의 `-e`는 **스크립트 env**(시나리오가 `__ENV`로 읽는 `BASE_URL` 등)다. 섞으면 remote-write 대상이 덮이지 않는다.
> `--no-deps`는 compose의 `depends_on: prometheus` 때문에 쓸모없는 로컬 Prometheus가 함께 뜨는 것을 막는다.

```bash
# (b) 좌석 조회 — 인증 불필요
docker compose run --rm --no-deps \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://<PROM_HOST>:9090/api/v1/write \
  k6 run \
  -e BASE_URL=https://<aws-gateway> \
  -e PERF_ID=1 -e VUS=50 \
  /scripts/scenarios/seat-layouts.js

# (a) 예매 생성 — booking-create 가 배포본에 포함돼 있을 때
docker compose run --rm --no-deps \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://<PROM_HOST>:9090/api/v1/write \
  k6 run \
  -e BASE_URL=https://<aws-gateway> \
  -e PERF_ID=1 -e LOAD_USER_PASSWORD='<평문>' \
  -e SEAT_ID_MIN=1 -e SEAT_ID_MAX=6000 \
  /scripts/scenarios/booking-create.js
```

관측은 5장 쿼리를 그대로 쓴다. `k6_*`와 `ticketrush_*`가 같은 TSDB에 있으므로 한 대시보드에서 겹쳐 본다.

> ⚠️ **부하 테스트 중에는 Grafana 대시보드를 열어두지 않는다.** 패널마다 주기적으로 쿼리가 나가 측정 대상의 CPU를 소모한다. 데이터는 TSDB에 쌓이므로 **테스트가 끝난 뒤** 열어 본다.

### 7.3 비용
비테스트 시간엔 AWS 리소스를 **반드시 중지**하는 **온디맨드 전제**로 운용한다. 상시 기동 시 24/7 과금된다.

### 7.4 보안
부하 대상 공인 엔드포인트와 인증 없는 Prometheus 9090은 **측정 시간에만** 열고, 끝나면 접근을 닫는다. 보안 그룹은 본인 공인 IP `/32`로 제한한다. 부하테스트용 DB·환경을 운영과 분리한다. 부하테스트 계정 비밀번호 평문은 저장소에 남기지 않는다.
