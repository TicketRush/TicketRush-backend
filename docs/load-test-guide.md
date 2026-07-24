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

두 스크립트 모두 **오실행 가드**가 걸려 있다. `@i_confirm_loadtest_db=1`을 명시하지 않으면 `ERROR 1146`으로 즉시 중단되고 본문은 돌지 않는다. 시드가 실제로 로그인되는 계정을 만들기 때문에, 운영 DB에 잘못 실행하면 그대로 백도어가 된다.

```bash
GUARD='SET @i_confirm_loadtest_db=1'

# 시딩 (idempotent: 마커 LOADTEST 기준, 재실행은 cleanup 먼저)
mysql -h 127.0.0.1 -u "$MYSQL_USERNAME" -p"$MYSQL_PASSWORD" \
  --init-command="$GUARD" ticket_rush < load-test/seed/seed_load.sql

# 리셋(규모 변경/정리) → 반드시 cleanup 먼저, 그다음 재시딩
mysql -h 127.0.0.1 -u "$MYSQL_USERNAME" -p"$MYSQL_PASSWORD" \
  --init-command="$GUARD" ticket_rush < load-test/seed/cleanup_load.sql
```

시딩 끝에 검증 카운트(load_users/performances/seats/bookings)가 출력된다. `@booking_pct>0`이면 이슈의 "PENDING 예매 대량 생성(만료/조회/인덱스 측정)" 요건을 만족한다.

시드는 공연·좌석과 함께 **부하테스트 전용 계정 1개**(`loadtest@ticketrush.local`)를 만든다. `booking-create`가 이 계정으로 로그인한다.

> 비밀번호 **평문은 커밋하지 않는다.** `seed_load.sql`의 `@load_pw_hash`(bcrypt cost 10)와 쌍을 이루며, 평문은 팀 내부에서만 공유하고 k6 실행 시 `-e LOAD_USER_PASSWORD=...`로 넘긴다.
> bcrypt 해시에는 salt가 그대로 들어 있어 저장소를 가진 사람은 무제한 오프라인 대입을 할 수 있다. 평문은 **길고 무작위**여야 하며, 다른 계정·환경에서 재사용하지 않는다.

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
- Prometheus를 로컬에 둘 수는 없다. pull 방식이라 앱 8개의 actuator 포트를 인터넷에 열어야 하기 때문이다([ADR 0004](adr/0004-load-test-execution-topology.md)가 대안 3을 기각한 이유와 같다).

> **관측 스택은 측정 대상과 같은 EC2에 있고, 관측 포트(9090·3000)는 인터넷에 열려 있지 않다** ([ADR 0007](adr/0007-observability-stack-colocation.md)). **SSH 터널을 먼저 띄운 뒤** k6를 실행한다.
>
> 아래의 `<PROM_HOST>`는 EC2의 주소가 아니라 **터널 입구**다. k6를 어떻게 돌리느냐로 갈린다.
>
> | k6 실행 방식 | `<PROM_HOST>` |
> |---|---|
> | compose 컨테이너 (아래 예시) | `host.docker.internal` |
> | 호스트에 설치한 k6 바이너리 | `localhost` |

### 7.1 사전 조건 (AWS)
- 부하 대상 앱을 AWS에 배포하고 게이트웨이 공인 엔드포인트를 확보한다. EC2에 탄력적 IP가 붙어 있어 인스턴스를 껐다 켜도 주소는 바뀌지 않는다.
- 관측 스택(Prometheus·Grafana)은 배포본 Compose에 함께 뜬다(`deploy/docker-compose.prod.yml`). 배포본은 `monitoring/prometheus.aws.yml`을 쓰며, 타깃이 컨테이너 서비스명 + actuator 포트(`gateway-service:8090` …)다. 로컬 개발용 `monitoring/prometheus.yml`과는 별개 파일이다.
- **인터넷에 열린 포트는 8080(gateway)뿐이다.** Prometheus(9090)·Grafana(3000)는 `127.0.0.1` 바인딩이라 보안 그룹에 룰을 추가할 필요가 없다. **SSH 터널로 접근한다.**

  ```bash
  # 부하 테스트 내내 이 터널을 띄워둔다. k6의 remote-write도 이 터널로 나간다.
  ssh -i <key>.pem \
    -L 3000:localhost:3000 \
    -L 9090:localhost:9090 \
    <user>@<EC2_탄력적_IP>
  # → Grafana: http://localhost:3000
  ```

  > ⚠️ **k6를 compose 컨테이너로 돌리는 경우**, 컨테이너에서 호스트의 터널에 닿아야 한다. `host.docker.internal`로 접근이 안 되면 터널 바인딩을 넓힌다(`ssh -L 0.0.0.0:9090:localhost:9090`). 이때 9090이 **내 PC의 LAN**에 열리므로 공용 네트워크에서는 쓰지 않는다.
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
**Prometheus 9090과 Grafana 3000은 인터넷에 열지 않는다**(`127.0.0.1` 바인딩 + SSH 터널, [ADR 0007](adr/0007-observability-stack-colocation.md)). remote-write receiver와 조회 API에는 인증이 없으므로 보안 그룹에 9090 룰을 추가하지 않는다.

인터넷에 열리는 것은 부하 대상인 gateway 8080뿐이다. 이것도 **측정 시간에만** 열고, 끝나면 접근을 닫는다. 보안 그룹은 본인 공인 IP `/32`로 제한한다. 앱의 actuator는 prod에서 8090로 분리되어 publish 되지 않으므로 `/actuator/prometheus`가 인터넷에서 읽히지 않는다.

부하테스트용 DB·환경을 운영과 분리한다. 부하테스트 계정 비밀번호 평문은 저장소에 남기지 않는다.

## 8. 장애 주입 — 발행 유실 측정 (#346)

예매 생성 부하 중 Kafka 브로커를 강제 종료해, AFTER_COMMIT(`kafka`) 모드의 발행 유실과 트랜잭셔널 Outbox(`outbox`) 모드의 유실 0건(at-least-once)을 정량 비교한다. 자산은 `load-test/chaos/`:

| 파일 | 용도 | 실행 위치 |
|---|---|---|
| `broker-outage.sh` | 브로커 정지 → N초 후 재시작, 구간 타임스탬프 출력 | EC2 (배포본 호스트) |
| `verify-loss.sql` | 유실 집계 — booking 생성 수 vs seat-group inbox 수신 수 | DB 접근 가능한 곳 |
| `booking-outbox.override.yml` | booking-service outbox 토글 (compose override) | EC2 `deploy/` 디렉토리 |

### 8.1 원리

- **kafka(AFTER_COMMIT) 모드**: 커밋 후 비동기 send, 실패는 error 로그뿐(§4.1). 커밋됐지만 send가 끝내 실패한 이벤트가 **유실**이다.
- 프로듀서는 기본 `delivery.timeout.ms=120s` 동안 버퍼·재시도한다(`KafkaConfig`에 오버라이드 없음). 브로커가 2분 안에 복귀하면 버퍼된 send가 성공해 유실이 안 나온다 — **정지 시간은 반드시 120초 초과**(기본 180초).
- **outbox 모드**: 발행 기록이 비즈니스 커밋에 포함되고 relay(5s)가 `PENDING`·`FAILED`를 재시도 → 유실 0. 대신 재시도로 **중복 발행이 정상적으로 발생할 수 있다**(at-least-once). 중복 차단은 소비 측 Inbox(§5) 몫이다.
- **ground truth는 DB다**: 기대 이벤트 수 = 구간 내 booking 생성 수(예매 1건 커밋 = `BookingCreatedEvent` 1건), 실수신 수 = seat-group `inbox` 기록 수. k6 지표는 부하 형상 확인용이다.

### 8.2 절차 (EC2 배포본, §7 토폴로지)

0. **터널**: §7의 3000·9090에 더해 **8080도 터널로** 연다(배포본 gateway는 `127.0.0.1` 바인딩). k6를 compose 컨테이너로 돌리면 컨테이너→호스트 터널 접근 제약(§7.2의 `host.docker.internal` 주의)이 동일하게 적용된다.
1. 시딩(§3, EC2 DB 대상) 후 **전 서비스의 `app.event-publisher.type` 현재값을 기록**한다(리포트 필수 항목).
2. **Phase A — kafka 모드(현행 배선) 유실 재현**
   - 구간 시작 시각(DB 시간 기준) 기록 → `booking-create.js` 부하 시작(정상 구간 ≥5분)
   - 부하 유지 중 EC2에서 `OUTAGE_SEC=180 ./broker-outage.sh`
   - 브로커 복구 후 ≥5분 유지 → 부하 종료, 구간 종료 시각 기록
   - `verify-loss.sql` 실행 → `lost_events > 0` 재현 확인. 정지 중에도 booking API가 201을 반환하는 것이 정상이다 — 커밋은 성공하고 발행만 사라지는 것이 곧 유실 창이다.
3. **Phase B — outbox 모드 유실 0 검증**
   - `docker compose -f docker-compose.prod.yml -f booking-outbox.override.yml up -d booking-service`
   - booking actuator에 `ticketrush_outbox_backlog`가 노출되는지 확인(outbox 모드에서만 등록됨)
   - Phase A와 동일 부하 + 동일 장애 주입 → 복구 후 `ticketrush_outbox_backlog`가 0으로 소진될 때까지 관측
   - `verify-loss.sql` → `lost_events = 0` 확인. 중복은 8.3의 PromQL 델타로 센다(0건일 수 있다 — 중복은 허용이지 보장이 아니다).
4. **원복**: `docker compose -f docker-compose.prod.yml up -d booking-service` (실제 outbox 전환은 #471 소관).

### 8.3 관측 PromQL

```promql
rate(k6_http_reqs_total[1m])                                   # 부하 형상
ticketrush_outbox_backlog                                      # outbox 적체 → 복구 후 0 소진
increase(ticketrush_outbox_relay_total{result="fail"}[<구간>])  # relay 재시도(실패) 횟수
increase(ticketrush_kafka_inbox_total{result="duplicate",consumer_group="seat-group"}[<구간>])  # 중복 수신
increase(ticketrush_kafka_dlt_total[<구간>])                    # DLT 유입(소비 실패)
```

### 8.4 주의

- **DEAD 전이**: `app.outbox.max-retries=3` 초과 시 `DEAD`가 되고 relay가 더는 집지 않는다. 정지 180초에서는 시도당 실패 판정에 delivery.timeout(120초)이 걸려 보통 1~2회 실패로 끝나지만, 정지를 길게 잡으면 나올 수 있다. `verify-loss.sql` ③에서 DEAD가 보이면 유실이 아니라 **"수동 개입 필요"로 분리 기록**하고, `status='PENDING'`으로 되돌려 소진을 재확인한다.
- **구간 분리**: Phase A/B 측정 구간이 시간상 겹치면 inbox 대조가 오염된다. `verify-loss.sql`은 드레인 지연 때문에 inbox를 실행 시점까지 세므로, 반드시 backlog 소진 후 실행한다.
- **타임존**: `verify-loss.sql`의 `@from/@to`는 **앱이 기록하는 `created_at` 기준(프로드 컨테이너는 UTC)**으로 준다. DB 세션 `NOW()`는 KST라 그대로 쓰면 구간이 안 맞아 조용히 0이 나온다.
- **IMAGE_TAG 명시**: EC2 `deploy/.env`의 `IMAGE_TAG`는 CD가 배포 시점에만 주입하고 갱신하지 않아 실행 중 컨테이너보다 **뒤처져 있을 수 있다**. override 적용/원복의 `docker compose up`은 booking의 의존 서비스(seat·performance)까지 재생성하므로, 스테일 태그면 그 서비스들이 조용히 구버전으로 다운그레이드된다(실측 중 actuator 8090 분리 이전 이미지로 내려간 사례 있음). 반드시 현재 컨테이너 태그를 확인(`docker inspect gateway-service --format '{{.Config.Image}}'`)하고 `IMAGE_TAG=<그 태그> docker compose ... up -d booking-service`로 명시한다.
- Grafana 대시보드는 측정 중 열어두지 않는다(§7.2와 동일).

## 9. Inbox 멱등성 측정 — 중복 차단율·이중 발급 0건 (#347)

consumer group offset을 earliest로 되돌려 토픽 누적 이벤트 전체를 **원본 eventId 그대로** 재전달시키고, Inbox(`uk_inbox_group_event`)가 전량 차단하는지 `kafka.inbox` 메트릭 델타로 정량화한다. #346의 Outbox(유실 0, 중복 허용)와 짝 — 그 중복을 걸러 실질 effectively-once를 완성하는 것이 Inbox다. 자산은 `load-test/chaos/`:

| 파일 | 용도 | 실행 위치 |
|---|---|---|
| `inbox-redeliver.sh` | 컨슈머 정지 → offset reset → 기동 → lag 소진 사이클을 DURATION 동안 반복 | EC2 (배포본 호스트) |
| `verify-inbox.sql` | 티켓 이중 발급 검사 + inbox 건수 sanity (재전달 전후 불변 확인) | DB 접근 가능한 곳 |

### 9.1 원리

- **재전달 유발**: `kafka-consumer-groups --reset-offsets --to-earliest`는 그룹의 커밋 오프셋만 되돌린다. 토픽의 기존 레코드가 원본 `eventId`로 다시 소비되므로, at-least-once 재전달을 브로커 장애 없이 대량·결정적으로 재현한다(#346의 브로커 정지 방식은 실측에서 중복 1건뿐이라 "대량" 조건에 부적합).
- **차단 경로**: 재전달 이벤트는 `InboxService.runIfFirst`의 exists fast-path에서 걸러진다 → 비즈니스 미실행, `kafka.inbox{result="duplicate"}` 증가. 테이블에는 첫 1건만 남으므로 **차단 건수는 SQL이 아니라 Prometheus 델타로 센다**.
- **측정 대상 그룹**: 주 대상은 `seat-group`×`booking-created-topic` — k6 `booking-create.js`가 이벤트 볼륨을 확보해 주는 유일한 경로다(결제확정 이벤트는 대량 생성 수단이 없다). 차단 로직·계측은 `runIfFirst` 한 곳이라 어느 그룹이든 동일 코드의 측정이다. `ticket-group`×`payment-confirmed-topic`은 잔존 이벤트로 reset 1회를 보조 증적으로 남기고, 티켓 이중 발급 0건 자체는 `verify-inbox.sql` ①과 동시성 테스트(`TicketIssueConcurrencyTest`)가 검증한다.
- **5분 유지**: reset 1회분은 fast-path(exists 1쿼리)라 수 분 내 소진될 수 있다. `inbox-redeliver.sh`가 lag 0 수렴을 폴링해 즉시 다음 사이클을 돌므로 DURATION(기본 360초) 동안 duplicate 트래픽이 끊기지 않는다 — 스크랩 간격(1분 가정) 대비 안정적 증분 조건.

### 9.2 절차 (EC2 배포본, §7 토폴로지)

1. 시딩(§3) 후 `booking-create.js` 부하(§7)로 `booking-created-topic`에 이벤트를 누적한다(예: 10분 ≈ 9,000건). **시딩·측정은 같은 세션에서** — inbox retention(30일) 이전 이벤트는 row가 purge돼 duplicate가 아니라 재처리가 된다.
2. 측정 전 `verify-inbox.sql` ②를 떠서 inbox 건수를 기록한다(사후 불변 대조용).
3. `DURATION=360 ./inbox-redeliver.sh` 실행(기본 seat-group). 출력의 UTC 타임스탬프가 측정 구간이다. k6 부하를 병행하면 신규(processed) 이벤트도 흘러 차단율이 100% 고정이 아닌 실측값이 된다(권장).
4. Grafana Explore에서 9.3의 duplicate 곡선이 구간 내내 끊기지 않는지 확인 후 캡처.
5. (보조) `GROUP=ticket-group TOPIC=payment-confirmed-topic SERVICE=ticket-service DURATION=60 ./inbox-redeliver.sh`로 reset 1회 수행, 같은 방식으로 기록.
6. `verify-inbox.sql` 실행 → ① 0행(이중 발급 없음), ② 사전 기록과 불변(재처리 없음) 확인.
7. 증적을 `load-tests/k6/results/<YYMMDD>-347-inbox-idempotency/`에 기록(report.md·metadata.txt·캡처 — #346 디렉토리 구성 답습).

### 9.3 관측 PromQL

```promql
# 중복 차단 건수 (측정 구간)
sum(increase(ticketrush_kafka_inbox_total{result="duplicate",consumer_group="seat-group"}[<구간>]))
# 중복 차단율 = duplicate/(duplicate+processed) — 라벨이 2종뿐이라 분모는 전체 합과 동치
sum(increase(ticketrush_kafka_inbox_total{result="duplicate",consumer_group="seat-group"}[<구간>]))
/ sum(increase(ticketrush_kafka_inbox_total{consumer_group="seat-group"}[<구간>]))
# 연속성 확인(캡처용) — 사이클 간 공백이 크면 유지 시간 미충족
sum(rate(ticketrush_kafka_inbox_total{result="duplicate",consumer_group="seat-group"}[1m]))
```

### 9.4 주의

- **컨슈머 재시작 부수효과**: `SERVICE` 재시작 동안 같은 서비스의 **다른 토픽 리스너도 함께 멈춘다**(예: seat-service의 결제 이벤트 처리 지연). 측정 전용 스택에서만 실행하고, 운영 트래픽과 겹치지 않게 한다.
- **reset은 그룹 비활성 필요**: 컨슈머가 살아 있으면 reset이 거부된다(`Error: Assignments can only be reset if the group ... is inactive`). 스크립트가 정지→reset→기동 순서를 보장하지만, 수동 실행 시 주의.
- **재처리 검출**: inbox 건수가 재전달 후 늘었다면 duplicate 차단이 아니라 재처리가 일어난 것이다(retention purge·컨슈머 그룹명 오타 등). 차단율 계산 전에 ② 불변부터 확인한다.
- **IMAGE_TAG 명시**: §8.4와 동일 — 컨슈머 서비스를 compose로 재생성할 일이 생기면 반드시 현재 태그를 명시한다(스크립트의 `docker stop/start`는 재생성이 아니라 무관).
- Grafana 대시보드는 측정 중 열어두지 않는다(§7.2와 동일).

## 10. 동일 좌석 동시성 측정 (#344)

### 10.1 이슈 서사와 실측의 차이 — 먼저 읽을 것

이슈 #344의 제목은 "락 경합 차단율"이지만, **실측에서 `ticketrush_seat_lock_contention_total`은 0이다. 버그가 아니라 구조다.**

홀드까지의 경로는 이렇다. `POST /api/v1/booking`과 좌석 HOLD 사이에 **비동기 구간이 두 개** 있다.

```
[유입 축]  POST /api/v1/booking
             → booking INSERT + outbox INSERT (한 트랜잭션, #471)
[처리 축]  → OutboxRelayScheduler   @Scheduled(fixedDelay=5000), app.outbox.batch-size=100
             → Kafka booking-created-topic (key = bookingId)
               → seat-service BookingCreatedEventListener  @KafkaListener(groupId=SEAT), concurrency 1
                 → InboxService.runIfFirst
                   → SeatFacade.tryLockSeat → SeatLockUseCase (tryLock 대기 0, lease 5분)
```

- 좌석 홀드는 HTTP API가 아니다. `SeatController`는 GET만 있고, 홀드 진입점은 `BookingCreatedEventListener`의 Kafka 리스너뿐이다. k6는 `POST /api/v1/booking`으로 **간접 유발만** 할 수 있고, 그 201 응답은 "예매 생성됨"이지 "좌석 선점됨"이 아니다.
- **릴레이가 유입을 5초 단위로 정형화한다**(#471). booking-service는 이벤트를 즉시 발행하지 않고 outbox 행으로 커밋한 뒤, `OutboxRelayScheduler`가 5초마다 최대 100건씩 꺼내 발행한다. 따라서 k6가 초당 수백 건을 밀어넣어도 Kafka로 나가는 속도는 **≈20 events/s로 상한**이 걸리고, 초과분은 `ticketrush_outbox_backlog`에 쌓인다. 이슈가 세운 "1차 병목은 락이 아니라 소비 병렬도"라는 가설은 이제 **릴레이 배치 주기까지 포함해** 확인해야 한다 — 릴레이가 컨슈머보다 앞에서 조이면 컨슈머 랙은 애초에 크게 자라지 않는다.
- 그 리스너의 컨슈머 스레드는 **1개**다. `common/.../config/KafkaConfig.java`의 컨테이너 팩토리에 `setConcurrency`가 없고 `spring.kafka.listener.concurrency`도 어디에도 없어 기본값 1이 적용된다(#466이 추가한 것은 Micrometer 리스너뿐이고 동시성은 건드리지 않았다).
- Redisson `RLock`은 `(clientUUID:threadId)` 기준 **재진입** 락이다. 같은 스레드가 1,000건을 순차 처리하면 `tryLock`은 실패하지 않고 **재진입으로 성공**한다. 카운터는 `tryLock == false` 경로에서만 오르므로(`SeatLockUseCase`) 구조적으로 0이다.

**따라서 동시성 방어선은 락이 아니다.** 실제로 중복 선점을 막는 것은 둘이다.

1. `SeatHoldUseCase`의 `isAvailable()` 체크 → `ticketrush_seat_hold_total{result="unavailable"}`
2. `Seat.version` 낙관적 락(#427) → 커밋 시점 충돌 → 롤백 → Kafka 재시도로 수렴

**"차단율"은 §10.3의 `unavailable` 비율로 산출한다.** 락 경합률로 산출하면 항상 0%가 나와 방어선이 없다는 뜻으로 오독된다. 락은 정합성 장치가 아니라 경쟁을 앞단에서 걸러내는 **성능 최적화**이며, 정합성의 최종 방어선은 DB다 — 이 구분의 SSOT는 [ADR 0008](adr/0008-redis-spof-acceptance.md)과 `Seat.version` javadoc이다.

> 컨슈머 동시성을 올리면(`setConcurrency > 1`) 이 수치는 달라진다. 그때 락 경합이 실제로 설계대로 작동하는지는 `SeatHoldConcurrencyTest`(seat-service)가 스레드를 갈라 검증해 둔 상태다. oversell 0 자체의 증명도 같은 테스트가 실 MySQL로 수행한다(Redis 락을 제거한 두 번째 케이스).
>
> 참고로 `BookingCreatedEvent.key()`는 `bookingId`다. 파티션을 늘리는 순간 같은 좌석 이벤트가 여러 파티션에 흩어지므로, "파티션 순차 처리 덕분에 안전하다"는 서사는 성립하지 않는다. 지금 안전한 이유는 순전히 컨슈머 스레드가 1개이기 때문이다.

### 10.2 실행

**토폴로지는 §7을 따른다 — k6는 로컬, 대상 앱은 AWS 배포본이다([ADR 0004](adr/0004-load-test-execution-topology.md)).** 앱까지 로컬로 돌리는 "전부 로컬"은 ADR 0004가 **대안 1로 검토한 뒤 기각한 방식**이다(생성기와 대상이 CPU를 경쟁해 latency·throughput을 신뢰할 수 없다). 로컬 기동은 시나리오가 도는지 보는 기능 확인용으로만 쓰고, **거기서 나온 RPS·p99는 §10.5에 적지 않는다.**

`local` 프로파일 전용 DevToken(`/api/v1/dev/auth/token`)으로 인증을 우회하지 않는다 — AWS 배포본은 `prod` 단독이라 그 엔드포인트가 없고, ADR 0004가 부하 테스트에 쓰지 않기로 결정했다. write 시나리오에는 `LOAD_USER_PASSWORD` 평문이 필요하다.

사전 조건은 §7.1과 같다(EC2 기동, SSH 터널, AWS 부하테스트용 DB 시딩).

**배포본 DB·Redis 는 EC2 안에서만 닿는다.** prod compose(`deploy/docker-compose.prod.yml`)의 mysql·redis 에는 `ports` 매핑이 없어 도커 네트워크 밖에서는 접속할 수 없다. §3 의 `mysql -h 127.0.0.1` 은 로컬 스택 기준이고, 배포본에는 아래처럼 SSH + `docker exec` 로 들어간다. 비밀번호는 컨테이너의 환경변수를 그 안에서 펼쳐 쓰므로 명령줄·셸 히스토리에 평문이 남지 않는다.

```bash
SEAT=121   # TARGET_SEAT_ID
SSH="ssh -i <key>.pem ubuntu@<EC2_IP>"
# 컨테이너 안에서 $MYSQL_ROOT_PASSWORD 를 펼치려고 작은따옴표로 넘긴다.
SQL() { $SSH "docker exec -i ticketrush-mysql sh -c 'mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" -N ticket_rush'"; }

# (1) 리셋 — 매 실행 전 필수. 배포본 DB와 Redis 락을 함께 되돌린다.
#     좌석은 한 번 HOLD 되면 스스로 AVAILABLE 로 돌아오지 않고, Redisson 락 키는 성공 경로에
#     unlock 이 없어 TTL 5분간 살아남는다. DB만 되돌리면 2회차 홀드가 락 획득에 실패해
#     전부 보상 처리되어 측정이 무의미해진다.
#     outbox 를 먼저 지우는 이유는 #471(발행 outbox 모드) 때문이다. 부하 종료 시점에 아직
#     발행되지 않은 PENDING 행이 남아 있으면, booking 행만 지워도 릴레이가 다음 회차에 그걸
#     그대로 뱉어 전 회차 이벤트가 새 측정 창에 섞인다. booking 삭제보다 먼저 지운다
#     (aggregate_id 로 대상을 찾아야 하므로 순서가 뒤바뀌면 대상을 잃는다).
#     ⚠ 이 DELETE 들에는 seed_load.sql 같은 가드가 없다. 범위를 좁히는 것은 seat_id 조건뿐이므로
#       $SEAT 값을 반드시 눈으로 확인하고 실행한다(--init-command 가드는 인라인 -e 에 효력이 없다).
SQL <<SQLEOF
DELETE FROM outbox
 WHERE event_type = 'BookingCreatedEvent'
   AND aggregate_id IN (SELECT CAST(booking_id AS CHAR) FROM booking WHERE seat_id = $SEAT);
DELETE FROM booking WHERE seat_id = $SEAT;
UPDATE seat SET seat_status='AVAILABLE', booking_number=NULL, hold_expired_at=NULL
 WHERE seat_id = $SEAT;
SQLEOF

# Redis 도 배포본 compose 안에 있다(로컬 redis 가 아니다).
$SSH "docker exec ticketrush-redis redis-cli DEL 'seat:lock:$SEAT'"

# (2) 부하 — STEADY 는 5분 이상. Prometheus 스크랩 간격보다 짧으면 일관된 RPS·p99 를 얻을 수 없고,
#     이슈 #344 의 완료조건("각 부하 단계가 최소 5분 유지")도 충족하지 못한다.
#     -e 위치 규칙과 --no-deps 는 §7.2 와 동일하다.
docker compose run --rm --no-deps \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://<PROM_HOST>:9090/api/v1/write \
  k6 run \
  -e BASE_URL=https://<aws-gateway> \
  -e PERF_ID=3 -e LOAD_USER_PASSWORD='<평문>' \
  -e TARGET_SEAT_ID=$SEAT -e VUS=200 -e RAMP=30s -e STEADY=6m \
  /scripts/scenarios/seat-contention.js

# (3) 릴레이 소진 대기 — k6 종료 시점엔 outbox 에 미발행분이 남아 있다(5s/100건 상한).
#     PENDING 이 0 이 된 뒤에 검증해야 "처리가 끝난 상태"의 수치를 본다.
#     이 시각이 측정 창의 종점이므로 UTC 로 기록한다(§10.5 / metadata 의 WINDOW_END_SOURCE).
echo "SELECT status, COUNT(*) FROM outbox WHERE event_type='BookingCreatedEvent' GROUP BY status;" | SQL

# (4) oversell 검증 — 반드시 1
echo "SELECT COUNT(*) FROM seat WHERE seat_id=$SEAT AND seat_status='HOLD';" | SQL
```

> Windows Git Bash 에서는 `/scripts/...` 가 `C:/Program Files/Git/scripts/...` 로 치환된다. 경로 앞에 `//` 를 붙이거나(`//scripts/scenarios/seat-contention.js`) `MSYS_NO_PATHCONV=1` 을 준다. PowerShell 은 그대로 쓴다.

### 10.3 PromQL

부하 종료 후 Grafana Explore(§5)에서 실행한다. 측정 창 `[5m]`은 실제 실행 길이에 맞춘다.

**유입 축과 처리 축을 분리해 읽는다**(이슈 #344 완료조건). HTTP 지표에는 락 경합도 홀드 지연도 나타나지 않는다 — §10.1의 경로에서 보듯 그 둘은 릴레이·컨슈머 뒤에서 일어나고, `POST /api/v1/booking`은 outbox 행을 커밋한 순간 응답을 끝내기 때문이다.

**정합성 (좌석 결과)**

| 지표 | 쿼리 |
|------|------|
| **oversell** (좌석 1개 부하에서 정확히 1) | `sum(increase(ticketrush_seat_hold_total{result="success"}[5m]))` |
| **차단율** (실측 방어선) | `sum(increase(ticketrush_seat_hold_total{result="unavailable"}[5m])) / sum(increase(ticketrush_seat_hold_total[5m]))` |
| **홀드 성공률** | `sum(increase(ticketrush_seat_hold_total{result="success"}[5m])) / sum(increase(ticketrush_seat_hold_total[5m]))` |
| **락 경합** (0 예상 — §10.1) | `sum(increase(ticketrush_seat_lock_contention_total[5m]))` |
| **현재 HOLD 총수** | `ticketrush_seat_held` |

**유입 축 (HTTP — booking 생성 API)**

| 지표 | 쿼리 |
|------|------|
| **처리량 (RPS)** | `sum(rate(k6_http_reqs_total[1m]))` |
| **p99 latency (생성기)** | `k6_http_req_duration_p99` |
| **p99 latency (앱)** | `histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{uri="/api/v1/booking"}[1m])) by (le))` |

**처리 축 (릴레이 → Kafka → 컨슈머)**

| 지표 | 쿼리 |
|------|------|
| **릴레이 적체** (유입 대비 발행 지연) | `ticketrush_outbox_backlog` |
| **릴레이 발행률** | `sum(rate(ticketrush_outbox_relay_total{result="success"}[1m]))` |
| **컨슈머 랙** (#466 신규) | `max by (client_id, topic) (kafka_consumer_fetch_manager_records_lag{job="ticketrush-services", topic="booking-created-topic"})` |
| **컨슈머 소화율** | `sum(rate(ticketrush_kafka_inbox_total{result="processed", consumer_group="seat-group"}[1m]))` |

컨슈머 랙은 Grafana System 대시보드의 **Kafka Consumer Lag** 패널(`monitoring/grafana/dashboards/ticketrush-system.json`)에 이미 있으므로 캡처는 그 패널을 쓴다.

> **어느 축이 조이는지 판별한다.** 릴레이가 5초/100건이라 발행 상한이 ≈20 events/s다(§10.1). `outbox_backlog`가 계속 자라는데 컨슈머 랙이 낮게 유지되면 **1차 제약은 릴레이**이고, 랙이 backlog와 함께 자라면 **컨슈머 병렬도(1)**가 제약이다. 이슈가 세운 가설("병목은 락이 아니라 소비 병렬도")은 이 두 곡선의 대비로 확인/반증한다.

HTTP 사전 차단율(409)은 k6 요약의 `seat_conflict` Rate를 그대로 읽는다(전체 요청이 분모인 비율이다). `seat_accepted`도 같은 분모라 둘을 나누지 않는다 — 201 비율은 `seat_accepted`를 따로 읽는다.

> 차단율 분모에 `seat_lock_contention`을 넣지 않는 이유는 §10.1이다. 두 카운터는 분모가 다르다 — `seat_hold_total`은 DB 체크에 **도달한** 시도만, contention은 도달조차 못 한 시도만 센다.

### 10.4 에러율 집계 기준 — 정상 경합은 실패가 아니다

동일 좌석에 N건을 밀어넣으면 1건만 성공하고 나머지는 전부 차단된다. **이건 설계된 동작이므로 실패로 세면 안 된다** — 그대로 두면 실패율이 99.9%로 집계돼 "HTTP Request Failed" 지표의 해석이 무의미해지고, 진짜 장애(5xx·타임아웃)가 그 안에 묻힌다. #348의 "정상경합 제외 에러율 < 1%" 기준과 같은 선을 쓴다.

구현은 `seat-contention.js` 상단 한 줄이다.

```js
http.setResponseCallback(http.expectedStatuses(200, 201, 409));
```

- **409를 기대 응답에 넣는다.** k6는 기본적으로 2xx/3xx 외를 `http_req_failed`로 센다. 임계값(`rate<0.01`)을 푸는 대신 409만 기대 응답으로 옮기면, **5xx·401·타임아웃은 계속 실패로 잡힌다.** 임계값을 완화하는 방식과 결정적으로 다른 점이다.
- **200을 함께 넣는 이유**는 이 콜백이 파일 스코프가 아니라 해당 run의 http 모듈 **전역**이기 때문이다. `lib/auth.js`의 로그인(200)까지 덮으므로, 200을 빼면 `setup()`의 로그인이 실패로 오집계된다.
- 따라서 리포트의 에러율은 **"정상 경합(409) 제외 에러율"**이며, 분모는 전체 요청이다. 409 자체의 비율은 별도 커스텀 메트릭 `seat_conflict`로 따로 읽는다(§10.3).

앱 쪽 차단은 실패가 아니라 **결과 라벨**로 남는다 — `ticketrush_seat_hold_total{result="unavailable"}`. 이쪽이 §10.3의 차단율 분자다.

### 10.5 측정 결과

| 항목 | 값 |
|------|-----|
| 실행 일시 / 배포 이미지 태그 | 2026-07-24 (KST) / `6398a9d` (전 서비스 동일, #471 outbox 포함) |
| 측정 창 (UTC) / 창 종점 출처 | 12:31:17 ~ 12:38:22 (7분 05초) / k6 종료(12:37:53) 후 outbox `PENDING/FAILED=0` 첫 관측 |
| 프로파일 (VUS/RAMP/STEADY) | 100 / 5s / **6m** |
| 총 요청 / 201 / 409 | 95,684 / 1,324 (1.38%) / 94,359 (98.61%) |
| 정상경합 제외 에러율 (§10.4) | **0.00%** (0 / 95,684) |
| **oversell** | **0** (동시 보유 좌석 최대 1건) |
| 차단율 (unavailable / total) | **99.77%** (1,321 / 1,324) |
| 홀드 성공률 | 0.23% (3 / 1,324 — HOLD 3회는 TTL 만료 후 재획득이지 동시 선점이 아니다) |
| 락 경합 (0 예상) | **0** — 시리즈 자체가 생성되지 않음 |
| **유입 축** — RPS / p95 / p90 | 258.29/s (peak 353.05) / 911.79ms / 726.94ms |
| **처리 축** — backlog 피크 / 릴레이 발행률 / 컨슈머 랙 피크 / 소비율 | 634 / 20.98·s⁻¹ / **14** / 20.54·s⁻¹ |
| 1차 제약 판정 (릴레이 vs 컨슈머 — §10.3) | **릴레이**. 컨슈머 랙이 최대 14로 자라지 않는데 backlog는 634까지 쌓였다 — 이슈의 "병목은 소비 병렬도" 가설은 반증됐다 |
| Grafana 캡처 | `load-tests/k6/results/260724-344-seat-contention/graph-*.png` |

> **임계값 `p(95)<800`은 초과했다(911.79ms, FAIL).** 다만 피크 구간 호스트 CPU가 99.87%였다(#465 node-exporter). 단일 EC2에 앱 9개 + Kafka·MySQL·Redis·관측 스택이 함께 뜬 구성의 포화가 섞인 값이라, 앱 단독 지연으로 읽으면 오독이다.
>
> 부수 관측으로 **릴레이 발행 증폭 3.09배**(발행 4,090 vs outbox 행 1,324)를 확인했다. `relayBatch()`에 in-flight 표시가 없어 콜백의 SENT 전이가 5초 폴링보다 늦으면 같은 행이 재발행된다(`retry_count`는 전부 0 — 실패 재시도가 아니다). javadoc이 명시한 at-least-once 설계 그대로이며 Inbox가 전량 차단했지만, 단일 컨슈머 스레드가 유효 처리량의 68%를 중복 차단에 쓴다.

증적은 `load-tests/k6/results/260724-344-seat-contention/`에 있다(#346·#347 디렉토리 구성 답습 — `report.md`·`metadata.txt`·`k6-summary.txt`·`graph-*.png`·`timeseries-*.json`).
