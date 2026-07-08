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
- 부하 생성기와 대상이 같은 머신을 공유하므로 리소스가 경쟁한다. 큰 규모 부하는 별도 머신에서 이 k6 서비스 정의를 재사용해 실행하는 것을 권장한다.
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

## 7. 원격(분리) 실행 — Oracle Cloud + GitHub Actions

부하 생성기(k6)와 대상(앱)이 같은 머신을 쓰면 리소스 경쟁으로 수치를 신뢰하기 어렵다(6장 참고). 둘을 **서로 다른 머신으로 분리**하되 **비용 0원**으로 구성한다: k6는 GitHub Actions 러너에서, 앱 스택은 Oracle Cloud Always Free 인스턴스에서 돌린다.

```
k6 (GitHub Actions) --remote-write(공인)--> Oracle Prometheus :9090 --scrape--> Grafana :3000
        │                                          ↑
        └── BASE_URL(공인) ──> Oracle 앱(호스트, gateway :8080 …) /actuator/prometheus
```

기존 자산(`load-test/` 시나리오·시딩, compose의 prometheus/grafana)은 **그대로** 쓰고 실행 위치와 URL·네트워크만 원격에 맞춘다.

### 7.1 Oracle 인스턴스 준비 (사용자 수행, 콘솔/SSH)
- **인스턴스**: ARM Ampere A1(Always Free 한도 내 총 4 OCPU / 24GB RAM)로 생성. Always Free는 영구 무료(AWS 프리티어와 무관)이며, 한도는 진행 전 Oracle 공식 문서로 재확인한다.
  - 주의: ARM 인스턴스는 "Out of capacity"로 생성이 지연될 수 있다 → 리전/가용 도메인/시간대를 바꿔 재시도.
- **네트워크 개방**(Security List 또는 NSG): 부하 대상·수신 포트만 인바운드 허용.
  - `8080` — 게이트웨이(부하 대상 `BASE_URL`).
  - `9090` — Prometheus remote-write 수신. **소스 CIDR을 반드시 제한**(아래 7.5 보안 경고).
  - Grafana `3000`은 열지 말고 SSH 터널로 접근(7.4).

### 7.2 앱·관측 스택 기동 (Oracle 호스트)
앱은 (컨테이너화 #245 이전이라) 로컬과 동일하게 **호스트에서 직접 기동**한다. arm64 주의: infra 이미지(redis/kafka/prometheus/grafana)는 멀티아치라 그대로 뜨고, 앱을 도커로 빌드한다면 `docs/ecr.md`의 `--platform linux/amd64` 고정과 달리 **플랫폼 지정 없이 네이티브 arm64**로 빌드한다.

```bash
# 관측 스택 + 의존 인프라 (오버레이로 prometheus를 0.0.0.0:9090에 노출)
docker compose -f docker-compose.yml -f docker-compose.oracle.yml up -d prometheus grafana redis kafka

# MySQL + 대상 서비스(gateway 8080 / auth 8082 / booking 8084 / seat 8086)를 local/dev 프로파일로 기동.
#   DevToken(booking-create 시나리오가 사용)은 local/dev 프로파일에서만 노출된다.
# JVM heap: 24GB 안에 여러 서비스를 수용하도록 서비스별 힙 상한을 준다(현재 Dockerfile/compose엔 튜닝 훅 없음).
JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50" java -jar <service>.jar   # 또는 -Xmx2g 등
```

시딩은 3장과 동일하게 Oracle의 MySQL에 대해 수행한다.

### 7.3 GitHub Secrets 등록
리포 → Settings → Secrets and variables → Actions → New repository secret:

| Secret | 값(예) | 용도 |
|--------|--------|------|
| `LOADTEST_BASE_URL` | `http://<oracle-public-ip>:8080` | k6 대상 게이트웨이 |
| `LOADTEST_PROMETHEUS_RW_URL` | `http://<oracle-public-ip>:9090/api/v1/write` | k6 결과 remote-write 타겟 |

### 7.4 워크플로우 실행
GitHub → **Actions** 탭 → **Load Test (k6)** → **Run workflow**. 입력값으로 시나리오·VUs·램프 등을 지정한다(`booking-create`는 `user_id`/`seat_id_min`/`seat_id_max`도 사용). 워크플로우는 `grafana/setup-k6-action`으로 러너에 k6를 설치한 뒤 `load-test/scenarios/*`를 실행하고, 결과를 Oracle Prometheus로 remote-write 하며, `summary.json`을 Actions 아티팩트로 업로드한다.

Grafana 관측은 5장 쿼리를 그대로 쓴다. Oracle Grafana는 포트를 열지 말고 SSH 터널로 접근한다:
```bash
ssh -L 3000:localhost:3000 <user>@<oracle-public-ip>   # 이후 http://localhost:3000
```

### 7.5 보안 경고
공개된 `9090`은 **인증 없는 remote-write 수신** 엔드포인트다. Oracle Security List/NSG로 접근 소스를 제한하고, 측정이 끝나면 포트를 닫는다. 상시 노출이 필요하면 리버스 프록시 basic-auth 등 인증을 앞단에 둔다.
