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
- 게이트웨이 8080, auth 8082(DevToken), booking 8084, seat 8086
- DB 계정은 `.env.local`의 `MYSQL_USERNAME`/`MYSQL_PASSWORD`

> DevToken(`/api/v1/dev/auth/token`)은 `local`/`dev` 프로파일에서만 노출된다.

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

시딩 끝에 검증 카운트(performances/seats/bookings)가 출력된다. `@booking_pct>0`이면 이슈의 "PENDING 예매 대량 생성(만료/조회/인덱스 측정)" 요건을 만족한다.

## 4. k6 실행

k6는 `loadtest` profile 컨테이너로 실행한다. remote-write 관련 설정(`K6_OUT`, `K6_PROMETHEUS_RW_SERVER_URL`, `K6_PROMETHEUS_RW_TREND_STATS`)과 `BASE_URL`(→ `host.docker.internal:8080`)은 compose에 정의돼 있어 매번 `-o`/URL을 줄 필요가 없다.

스크립트는 `./load-test`가 컨테이너 안 `/scripts`로 마운트된다. 시나리오 파라미터는 `-e KEY=VALUE`로 덮는다 (`BASE_URL, PERF_ID, USER_ID, SEAT_ID_MIN, SEAT_ID_MAX, VUS, RAMP, STEADY`).

```bash
# (b) 좌석 조회 — 인증 불필요, 먼저 파이프라인 검증용
docker compose run --rm k6 run \
  -e PERF_ID=1 -e VUS=50 \
  /scripts/scenarios/seat-layouts.js

# (a) 예매 생성 — setup()에서 DevToken 자동 발급 후 Bearer 호출
docker compose run --rm k6 run \
  -e PERF_ID=1 -e USER_ID=1 -e SEAT_ID_MIN=1 -e SEAT_ID_MAX=6000 \
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

부하 생성기(k6)와 대상(앱)이 같은 머신을 쓰면 리소스 경쟁으로 수치를 신뢰하기 어렵다(6장 참고). k6는 **로컬에서 그대로 실행**하고, 대상 앱은 **AWS에 배포된 인스턴스**를 타격해 둘을 분리한다. 기존 `loadtest` 프로파일·시나리오·시딩은 그대로 쓰고 `BASE_URL`만 AWS 엔드포인트로 덮으면 된다.

```
k6 (로컬 loadtest 컨테이너) --HTTP--> AWS 배포 앱 (공인 게이트웨이 :8080)
        │
        └─ remote-write ─> 로컬 Prometheus :9090 --scrape--> 로컬 Grafana :3000   (k6_* 시계열)
```

- **k6 클라이언트 시계열(`k6_*`: 처리량·latency·에러율)**은 기존대로 로컬 Prometheus→Grafana로 remote-write 된다(설정 무변경).
- **앱 계측(`ticketrush_*`)은 로컬에서 스크랩되지 않는다** — 앱이 AWS에 있어 로컬 Prometheus의 `host.docker.internal` 스크랩 대상 밖이다. 앱 지표는 **AWS 측 모니터링(CloudWatch 등)에서 별도 확인**한다.

> **왜 방향을 반대로(k6=원격, 앱=로컬) 두지 않는가**
> k6를 원격(EC2 등)에 두고 로컬 앱을 타격하면 ① 응답 페이로드가 가정용 회선의 좁고 지터 큰 **업로드**를 거쳐, latency 측정이 앱이 아니라 집 네트워크를 재게 된다(분리의 목적을 무너뜨림). ② 로컬 게이트웨이·DevToken·Prometheus를 공개 노출해야 해 보안이 나빠진다. 대상을 데이터센터(AWS)에 두는 현재 방향은 응답(큰 쪽)을 넓은 **다운로드**로 받고 노출면도 종료 가능한 인스턴스로 격리돼 낫다.

### 7.1 사전 조건 (AWS)
- 부하 대상 앱을 AWS에 배포하고 게이트웨이 공인 엔드포인트(`https://<aws-gateway>`)를 확보한다.
- `seat-layouts`(read)는 **인증 불필요** → 배포 프로파일과 무관하게 바로 가능.
- `booking-create`(write)는 `DevToken(/api/v1/dev/auth/token)`을 사용하는데 이는 **`local`/`dev` 프로파일에서만 노출**된다. → AWS 배포본을 **`dev` 프로파일(부하테스트 전용 환경)**로 띄우지 않으면 write 시나리오는 **인증 실패**한다.
- 시딩은 3장과 동일하게 **AWS의 부하테스트용 DB**에 대해 수행한다(운영 DB 금지).

### 7.2 k6 실행 (로컬)
4장과 동일하되 `BASE_URL`만 AWS로 덮는다. remote-write는 로컬 prometheus로 그대로 나간다.
```bash
# 모니터링 스택 먼저 (2.2)
docker compose up -d prometheus grafana

# (b) 좌석 조회 — 인증 불필요
docker compose run --rm k6 run \
  -e BASE_URL=https://<aws-gateway> \
  -e PERF_ID=1 -e VUS=50 \
  /scripts/scenarios/seat-layouts.js

# (a) 예매 생성 — AWS가 dev 프로파일(DevToken 노출)일 때만 가능
docker compose run --rm k6 run \
  -e BASE_URL=https://<aws-gateway> \
  -e PERF_ID=1 -e USER_ID=1 -e SEAT_ID_MIN=1 -e SEAT_ID_MAX=6000 \
  /scripts/scenarios/booking-create.js
```
관측은 5장 쿼리를 그대로 쓴다(단, `ticketrush_*` 앱 지표는 AWS 측에서 확인).

### 7.3 비용
비테스트 시간엔 AWS 리소스를 내려두는 **온디맨드 전제**로: 세션당 ~3시간 × 월 8회 = 24시간/월 ≈ **$2.5(약 3,500원)**. **비테스트 시간엔 AWS 리소스를 반드시 중지**해야 이 비용이 성립한다(상시 기동 시 24/7 과금).

### 7.4 보안
부하 대상 공인 엔드포인트와 `dev` 프로파일(DevToken 노출)은 **측정 시간에만** 열고, 끝나면 접근을 닫는다. 부하테스트용 DB·환경을 운영과 분리한다.
