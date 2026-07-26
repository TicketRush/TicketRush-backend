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
- **릴레이가 유입을 5초 단위로 정형화한다**(#471). booking-service는 이벤트를 즉시 발행하지 않고 outbox 행으로 커밋한 뒤, `OutboxRelayScheduler`가 5초마다 최대 `app.outbox.batch-size`건씩 꺼내 발행한다. 따라서 k6가 초당 수백 건을 밀어넣어도 Kafka로 나가는 속도에 상한이 걸리고, 초과분은 `ticketrush_outbox_backlog`에 쌓인다. **아래 §10.5 수치는 `batch-size: 100` 시절(≈20 events/s)의 것이다** — 현행은 300(≈60 events/s)이므로 재측정 시 상한선을 다시 계산한다(#489, §11.8). 이슈가 세운 "1차 병목은 락이 아니라 소비 병렬도"라는 가설은 이제 **릴레이 배치 주기까지 포함해** 확인해야 한다 — 릴레이가 컨슈머보다 앞에서 조이면 컨슈머 랙은 애초에 크게 자라지 않는다.
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

# (3) 릴레이 소진 대기 — k6 종료 시점엔 outbox 에 미발행분이 남아 있다(5초/batch-size 상한).
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

> **"p99 latency (앱)"은 #495 이전 배포본에서 빈 결과를 냈다** — 히스토그램 버킷이 없었기 때문이다(§12.4). #495 이후 이미지에서만 값이 나오고, `slo` 경계(1ms~2s) 기반의 보간값이다.

**처리 축 (릴레이 → Kafka → 컨슈머)**

| 지표 | 쿼리 |
|------|------|
| **릴레이 적체** (유입 대비 발행 지연) | `ticketrush_outbox_backlog` |
| **릴레이 in-flight** (콜백 대기 건수, #483) | `ticketrush_outbox_in_flight` |
| **릴레이 발행률** | `sum(rate(ticketrush_outbox_relay_total{result="success"}[1m]))` |
| **컨슈머 랙** (#466 신규) | `max by (client_id, topic) (kafka_consumer_fetch_manager_records_lag{job="ticketrush-services", topic="booking-created-topic"})` |
| **컨슈머 소화율** | `sum(rate(ticketrush_kafka_inbox_total{result="processed", consumer_group="seat-group"}[1m]))` |

컨슈머 랙은 Grafana System 대시보드의 **Kafka Consumer Lag** 패널(`monitoring/grafana/dashboards/ticketrush-system.json`)에 이미 있으므로 캡처는 그 패널을 쓴다.

> **어느 축이 조이는지 판별한다.** 릴레이 발행 상한은 `batch-size` / 5초다(§10.1 — 이 회차는 100이라 ≈20/s, 현행 300은 ≈60/s). `outbox_backlog`가 계속 자라는데 컨슈머 랙이 낮게 유지되면 **1차 제약은 릴레이**이고, 랙이 backlog와 함께 자라면 **컨슈머 병렬도(1)**가 제약이다. 이슈가 세운 가설("병목은 락이 아니라 소비 병렬도")은 이 두 곡선의 대비로 확인/반증한다.

> **backlog만 보고 릴레이 정지를 단정하지 않는다**(#483). in-flight 가드(#445) 도입 후로는 발행을 띄우고 콜백을 기다리는 행도 PENDING으로 남아 backlog에 잡힌다. `outbox_in_flight`를 겹쳐 봐야 갈린다. Grafana System 대시보드의 **Outbox Backlog / In-Flight** 패널이 두 곡선을 같이 그린다.
>
> | backlog 높음 + in-flight | 판정 |
> |---|---|
> | 0~`batch-size` 사이에서 **출렁인다** | 정상. 발행은 나가고 있고 콜백을 기다리는 중이다 |
> | **`batch-size`(현행 300 — #489 이전 회차는 100)에 붙어 정체** | **슬롯 고갈**. 콜백이 유실돼 조회 윈도가 잠긴 상태다 — 새 행이 한 건도 못 나간다. 180초 스윕(`IN_FLIGHT_TIMEOUT_MS`)이 걷어낼 때까지 진행이 없고, 반복되면 프로듀서 쪽을 봐야 한다 |
> | **0에 고정** | 릴레이가 행을 집지 못하고 있다. 조회 실패나 스케줄러 정지를 의심한다 |
>
> **두 게이지가 동시에 얼어붙으면 값 자체를 믿지 않는다.** `backlog`는 `relayBatch()` 안에서만 갱신되므로(`backlog.set(...)`), 릴레이 스레드가 죽으면 마지막 값이 그대로 계속 노출된다 — 값이 낮다고 안전한 게 아니라 **관측이 멎은 것**이다. `rate(ticketrush_outbox_relay_total[1m])`가 0인데 두 게이지가 미동도 없으면 릴레이 자체가 돌지 않는다고 본다.

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
| 홀드 성공률 | 0.23% (3 / 1,324 — HOLD 3회는 해제 후 재획득이지 동시 선점이 아니다) |
| 락 경합 (0 예상) | **0** — 시리즈 자체가 생성되지 않음 |
| **유입 축** — RPS / p95 / p90 | 258.29/s (peak 353.05) / 911.79ms / 726.94ms |
| **처리 축** — backlog 피크 / 릴레이 발행률 / 컨슈머 랙 피크 / 소비율 | 634 / 20.98·s⁻¹ / **14** / 20.54·s⁻¹ |
| 1차 제약 판정 (릴레이 vs 컨슈머 — §10.3) | **릴레이**. 컨슈머 랙이 최대 14로 자라지 않는데 backlog는 634까지 쌓였다 — 이슈의 "병목은 소비 병렬도" 가설은 반증됐다 |
| Grafana 캡처 | `load-tests/k6/results/260724-344-seat-contention/graph-*.png` |

> **임계값 `p(95)<800`은 초과했다(911.79ms, FAIL).** 다만 피크 구간 호스트 CPU가 99.87%였다(#465 node-exporter). 단일 EC2에 앱 9개 + Kafka·MySQL·Redis·관측 스택이 함께 뜬 구성의 포화가 섞인 값이라, 앱 단독 지연으로 읽으면 오독이다.
>
> 부수 관측으로 **릴레이 발행 증폭 3.09배**(발행 4,090 vs outbox 행 1,324)를 확인했다. `relayBatch()`에 in-flight 표시가 없어 콜백의 SENT 전이가 5초 폴링보다 늦으면 같은 행이 재발행된다(`retry_count`는 전부 0 — 실패 재시도가 아니다). javadoc이 명시한 at-least-once 설계 그대로이며 Inbox가 전량 차단했지만, 단일 컨슈머 스레드가 유효 처리량의 68%를 중복 차단에 쓴다.

증적은 `load-tests/k6/results/260724-344-seat-contention/`에 있다(#346·#347 디렉토리 구성 답습 — `report.md`·`metadata.txt`·`k6-summary.txt`·`graph-*.png`·`timeseries-*.json`).

## 11. 대량 만료 청크 트랜잭션 측정 (#345)

만료 HOLD 좌석을 대량으로 시딩해, 좌석 만료 fallback이 그것을 소진하는 과정을 **청크 트랜잭션(25건) vs 단일 트랜잭션**으로 비교한다. **k6를 쓰지 않는다** — HTTP 부하가 아니라 시딩 + 스케줄러 관측이다.

### 11.1 이슈 서사와 실측의 차이 — 먼저 읽을 것

이슈 #345는 1만 건 시나리오의 주 지표를 "`seat_held` Gauge의 tick별 잔량 감소 곡선"으로 잡았다. **그 게이지로는 측정되지 않는다.**

```java
// SeatRepository.countHeldSeats — seat_held 게이지의 원천
select count(s) from Seat s where s.seatStatus = :hold and s.holdExpiredAt > :now
```

`seat_held`는 **미만료** HOLD만 센다. 만료됐지만 아직 해제되지 않은 좌석 — 정확히 이 측정이 재려는 적체 — 은 처음부터 게이지 밖이다. 만료 코호트를 시딩하면 `seat_held`는 0에서 시작해 0으로 끝나고 곡선이 아예 그려지지 않는다. #484가 고친 스케줄러 스레드 굶주림과는 별개 문제이며, 지표 정의 자체의 공백이다.

그래서 #345에서 **`ticketrush_seat_hold_expired_backlog` 게이지를 추가했다**(`SeatHeldGaugeMetrics`, 30초 갱신). `outbox_backlog`의 좌석 버전이고, 세는 집합의 비교 연산자(`hold_expired_at <= now`)를 스케줄러가 실제로 집어가는 `findExpiredHoldSeats`와 같게 맞춰 두 집합이 어긋나지 않게 했다. **적체 해소 곡선은 이 게이지로 읽는다.**

### 11.2 단일 트랜잭션 비교군은 코드 변경이 아니다

`chunkSize` × `maxChunks`가 곧 (트랜잭션 범위) × (tick당 청크 수)다. `chunk-size`를 코호트 전량으로, `max-chunks`를 1로 주면 `SeatReleaseExpiredUseCase`의 루프가 한 번만 돌아 전량이 단일 트랜잭션으로 커밋된다. 벤치용 브랜치도 토글 코드도 필요 없다 — compose override 하나뿐이다(`load-test/chaos/seat-release-singletrx.override.yml`).

**바인딩은 로그가 증명한다.** 만료 2,000건에 `chunk-size=2000`·`max-chunks=1`이면 `fetched == chunkSize`이고 `processedChunks == maxChunks`라 처리 상한 도달 경고가 `chunkSize=2000 x maxChunks=1`을 그대로 찍는다. actuator/env는 노출 대상이 아니라(health·info·prometheus 3개) 쓸 수 없으므로 이 로그가 유일한 확증 수단이다.

> **10,000건 단일 트랜잭션은 돌리지 않는다.** seat-service의 `mem_limit`이 640m이고, 단일 트랜잭션이 `SeatStatusScheduler`의 ShedLock `lockAtMostFor=2m`를 넘기면 락이 풀려 중복 실행 창이 열린다. 2,000건이 tick당 처리 상한과 같은 크기라 그 지점의 A/B가 정직한 대조다.

### 11.3 측정 매트릭스

| # | 만료 건수 | 설정 | 관측 | 주 지표 |
|---|---|---|---|---|
| A1 | 2,000 | 청크 25×80 (yml 기본값) | ≥5분 | 최장 trx 지속시간, `hikaricp_connections_pending` 피크 |
| A2 | 2,000 | **단일 2000×1** (override) | ≥5분 | 같은 작업량의 대조군 |
| B1 | 10,000 | 청크 25×80 | **≥10분** | `seat_hold_expired_backlog` tick별 감소 곡선(최소 5 tick 소진) |

### 11.4 사전 점검 (EC2 기동 후, 측정 전)

0. **⚠️ compose 는 반드시 `~/ticketrush/deploy/` 에서 실행한다. 리포 루트(`~/ticketrush/`)에 구버전 `docker-compose.prod.yml` 사본이 남아 있고, 그걸로 `up` 하면 Redis 가 비밀번호 없이 재생성되어 전 서비스 인증이 깨진다.** 실행 중 스택의 소유 경로는 라벨이 알려준다 — 손대기 전에 확인한다.
   ```bash
   docker inspect booking-service --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}'
   # → /home/ubuntu/ticketrush/deploy/docker-compose.prod.yml  (이 경로에서만 compose 를 돌린다)
   ```
   실측에서 실제로 밟았다. 루트 사본에는 `--requirepass` 가 없고 그 디렉토리의 `.env` 에는 `REDIS_PASSWORD` 키 자체가 없다(#426 이후 추가된 키다). `up -d seat-service` 가 `depends_on` 의 redis·mysql 까지 재생성하면서 Redis 가 인증 없이 떴고, 비밀번호를 보내는 나머지 앱들이 `ERR AUTH <password> called without any password configured` 로 전부 끊겼다 — seat-service 는 22회 재시작, `redis_up` 알림 발화. **복구는 정상 디렉토리에서 `up -d redis mysql seat-service`** 로 같은 프로젝트 이름(`ticketrush-prod`)에 재생성하면 된다(볼륨은 명시적 이름이라 데이터는 보존된다).
1. **`SHOW INDEX FROM seat`에 `idx_seat_status_hold_expired_at`이 있는지 확인한다.** `@Table`의 `@Index`는 `ddl-auto=update`인 신규 DB에서만 생기고 prod(`validate`)는 인덱스 부재를 검출하지 못한다(`Seat` javadoc, #296 관행). 없으면 아래를 먼저 적용하고 **metadata에 적용 여부를 기록**한다 — 인덱스가 없으면 청크 조회가 tick당 80회 풀스캔이 되어 측정값이 인덱스 결함과 섞인다.
   ```sql
   ALTER TABLE seat ADD INDEX idx_seat_status_hold_expired_at (seat_status, hold_expired_at),
     ALGORITHM=INPLACE, LOCK=NONE;
   ```
2. 게이지 포함 이미지가 배포됐는지: seat-service actuator(컨테이너 내 8090)의 `/actuator/prometheus`에 `ticketrush_seat_hold_expired_backlog` 노출 확인.
3. A1·B1은 yml 기본값이어야 한다 — `docker exec seat-service env | grep -E '^APP_SEAT_RELEASE_'` 가 비어야 정상.
4. §7의 SSH 터널(3000·9090). **측정 중 Grafana 대시보드는 열어두지 않는다.**
5. 현재 태그 확보: `TAG=$(docker inspect gateway-service --format '{{.Config.Image}}' | cut -d: -f2)`

### 11.5 절차 (회차마다 반복)

```bash
SSH="ssh -i <key>.pem ubuntu@<EC2_IP>"
SQL() { $SSH "docker exec -i ticketrush-mysql sh -c 'mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" -N ticket_rush'"; }
```

1. **시딩** — `seed_expired_holds.sql`은 리셋(outbox → seat → booking)과 시드를 한 파일에 담고 있어 매 회차 앞에 한 번만 돌리면 된다. outbox 리셋은 **미발행(PENDING/FAILED) 만료 이벤트만** 지운다 — SENT 는 릴레이가 다시 집지 않아 다음 창을 오염시키지 못하고, `aggregate_id IN (2,000건)` 으로 코호트를 특정하면 `(event_type, aggregate_id)` 인덱스가 없어 5만 행 풀스캔 두 번이 된다(실측에서 시딩이 회차당 **2분 30초 → 1초 미만**으로 줄었다). 코호트 크기는 파일 상단 `@expired_count`로 정한다(2000 / 10000). `seed_load.sql`이 만든 LOADTEST 좌석이 코호트 크기 이상 AVAILABLE로 남아 있어야 한다(끝의 검증 쿼리가 `requested`/`cohort_bookings`/`expired_hold_seats` 일치를 보여준다).
   ```bash
   $SSH "docker exec -i ticketrush-mysql sh -c 'mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" \
     --init-command=\"SET @i_confirm_loadtest_db=1\" ticket_rush'" < load-test/seed/seed_expired_holds.sql
   ```
   `seed_load.sql`의 `@booking_pct`는 `hold_expired_at = NOW() + 5분`(미만료)이라 이 측정에 쓸 수 없다. 코호트의 `created_at`은 `NOW()`로 둔다 — 과거로 당기면 booking-service의 `BookingExpireUseCase`(cutoff = now−5분)가 따로 물어 좌석 경로 측정이 오염된다. 예매 만료는 `SeatHoldExpiredEvent` 경유(프로덕션 경로)로만 일어난다.
2. **계측 2종을 tick 전에 준비한다.**

   **(a) 최장 트랜잭션 지속시간 — `performance_schema` (주 계측)**. 이게 이 측정의 핵심 수치다. 청크 트랜잭션은 수십 ms라 초 단위 폴링으로는 원리상 못 잡는다. 대신 P_S 요약 테이블을 **tick 직전에 truncate 하고 tick 직후에 읽으면** 그 창의 최댓값이 정확히 나온다(`transaction` instrument 가 기본 enabled·timed 다).
   ```bash
   SEATIP=$(docker inspect seat-service --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
   # 시딩 직후 = tick 직전에 리셋
   echo "TRUNCATE performance_schema.events_transactions_summary_by_thread_by_event_name;" | SQL
   # ... tick 이 만료 좌석을 소진할 때까지 대기 ...
   echo "SELECT SUM(s.COUNT_STAR) trx_count,
                ROUND(MAX(s.MAX_TIMER_WAIT)/1e9,1) max_ms,
                ROUND(SUM(s.SUM_TIMER_WAIT)/SUM(s.COUNT_STAR)/1e9,1) avg_ms
           FROM performance_schema.events_transactions_summary_by_thread_by_event_name s
           JOIN performance_schema.threads t ON t.THREAD_ID = s.THREAD_ID
          WHERE t.PROCESSLIST_HOST = '$SEATIP' AND s.COUNT_STAR > 0;" | SQL
   ```
   **`PROCESSLIST_HOST` 로 seat-service 커넥션만 거른다.** 전역 요약을 쓰면 다른 서비스의 릴레이·인박스 트랜잭션이 섞인다. 컨테이너를 재생성하면 IP 가 바뀌므로 회차마다 다시 뜬다.

   **(b) 락 점유 규모·락 대기 — `trx-sampler.sh`** (보조). 1초 간격으로 `innodb_trx` 의 `trx_rows_locked`·`trx_state='LOCK WAIT'`·`data_lock_waits` 를 남긴다. 지속시간은 (a)를 쓰고, 이 CSV 는 "몇 행을 잠갔나 / 대기가 있었나"에만 쓴다.
   ```bash
   DURATION=660 ./trx-sampler.sh > trx-samples-b1.csv   # load-test/bench/
   ```

   **(c) HikariCP 는 1초로 따로 떠야 한다.** Prometheus 스크랩이 15초라 2~6초짜리 tick 을 통째로 놓친다. actuator 를 직접 1초로 긁는다.
   ```bash
   while :; do
     printf '%s,' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
     docker exec seat-service sh -c \
       'curl -s localhost:8090/actuator/prometheus | grep -E "^hikaricp_connections_(pending|active|idle)" | awk "{print \$2}" | paste -sd,'
     sleep 1
   done > hikari-a1.csv
   ```
3. **관측** — `SeatStatusScheduler`는 `fixedDelay=60000`이라 시딩 후 최대 1분 안에 첫 tick이 돈다. A1/A2는 5분, B1은 10분 이상 유지한다.
4. **소진 확인** — 적체가 0이고 릴레이가 비었을 때가 측정 창 종점이다(§10.2 규약). UTC로 기록한다.
   ```bash
   echo "SELECT COUNT(*) FROM seat WHERE seat_status='HOLD' AND hold_expired_at <= UTC_TIMESTAMP();" | SQL
   echo "SELECT status, COUNT(*) FROM outbox WHERE event_type='SeatHoldExpiredEvent' GROUP BY status;" | SQL
   ```
   `NOW()`가 아니라 `UTC_TIMESTAMP()`다 — 아래 §11.7의 시계 항목을 먼저 읽을 것.
5. **A2만** — override 적용/원복(§11.2). 적용 후 seat-service 로그에서 `chunkSize=2000 x maxChunks=1` 경고를 확인한 뒤 측정한다.
   ```bash
   IMAGE_TAG=$TAG docker compose -f docker-compose.prod.yml -f seat-release-singletrx.override.yml up -d seat-service
   # ... 측정 ...
   IMAGE_TAG=$TAG docker compose -f docker-compose.prod.yml up -d seat-service   # 원복
   ```
6. 증적을 `load-tests/k6/results/<YYMMDD>-345-chunk-trx/`에 기록(#344 디렉토리 구성 답습).

### 11.6 PromQL

```promql
ticketrush_seat_hold_expired_backlog                 # 적체 해소 곡선 (B1 주 지표, §11.1)
ticketrush_seat_held                                 # 미만료 HOLD (대조 — 코호트 측정에선 0 유지가 정상)
hikaricp_connections_pending{application="seat-service"}   # 커넥션 풀 압박 피크
hikaricp_connections_active{application="seat-service"}
sum(rate(ticketrush_outbox_relay_total{result="success"}[1m]))  # 만료 이벤트 발행 소화
ticketrush_outbox_backlog / ticketrush_outbox_in_flight         # 겹쳐 읽는다 (§10.3 판별표)
```

HikariCP·트랜잭션 패널은 Grafana System 대시보드(`monitoring/grafana/dashboards/ticketrush-system.json`)에 이미 있다. 적체 게이지는 Explore로 캡처한다.

### 11.7 주의

- **⚠️ 앱은 UTC, MySQL 세션은 KST다 — 만료 시딩에 `NOW()`를 쓰면 조용히 헛돈다.** 배포본 MySQL 컨테이너는 `system_time_zone=KST`라 세션 `NOW()`가 앱 컨테이너 시계보다 **9시간 앞선다**. `hold_expired_at`은 앱의 `LocalDateTime.now()`와 비교되므로(`findExpiredHoldSeats`), `NOW()`로 시딩하면 좌석이 "9시간 뒤 만료"로 저장되어 **스케줄러가 매 tick 정상 동작하면서도 0건을 처리한다.** 에러도 경고도 남지 않고 로그에는 `Fallback 스케줄러 동작` 만 반복 찍힌다 — 첫 실측에서 실제로 3 tick을 그렇게 날렸다. `seed_expired_holds.sql`은 `@app_now = UTC_TIMESTAMP()`를 잡아 전 구간에 쓰고, 검증 쿼리가 `db_now`/`app_now_used`를 함께 출력한다. **첫 실행 때 `docker exec seat-service date`와 `app_now_used`가 같은지 눈으로 확인한다.** (§8.4의 `verify-loss.sql` 타임존 주의와 같은 뿌리다.)
- **`innodb_trx` 1초 샘플링으로는 청크 트랜잭션 지속시간을 잴 수 없다.** 청크 25건은 실측 수십 ms라 샘플 사이에 끝난다. 지속시간은 §11.5(a)의 `performance_schema` 로 재고, 샘플러는 락 점유 규모·대기 관측용으로만 읽는다. 샘플러 CSV 에 78초짜리 값이 보이면 시딩 자신의 대량 UPDATE 다 — 시딩 구간을 타임스탬프로 잘라내고 본다.
- **커넥션 풀 압박(`hikaricp_connections_pending`)은 이 경로에서 구조적으로 0이다.** 만료 fallback 은 스케줄러 스레드 **하나**가 도는 단일 스레드 경로라 커넥션을 한 개만 쓴다(실측: 두 arm 모두 `pending=0`, `active` 피크 1, `idle` 9). 풀 크기가 10이므로 단일 트랜잭션이 5초를 물고 있어도 대기가 생기지 않는다. **"청크 분할로 커넥션 풀 고갈을 회피한다"는 서술은 이 규모·이 경로에서는 실측으로 뒷받침되지 않는다** — 풀 압박을 보려면 만료 처리와 동시에 다른 요청이 커넥션을 다투게 만들어야 하고, 그건 이 시나리오 밖이다. 청크 분할의 실측 이득은 **락 보유 시간**이다.
- **`confirmSoldById` 블로킹은 직접 측정할 수 없다.** 결제 확정 이벤트를 대량 생성할 수단이 없다(§9.1이 같은 이유로 `ticket-group`을 보조 증적으로 돌린 전례). 락 점유는 샘플러의 `max_secs_running`(= 락 보유 상한)과 `lock_wait_trx`/`data_lock_waits` 관측 건수로 대신 기록하고, `application.yml` 주석이 근거로 든 경합은 구조적 논거 + 지속시간 수치로 서술한다. **추정을 수치로 위장하지 않는다.**
- 단일 EC2에 앱 9개 + Kafka·MySQL·Redis·관측 스택이 동거하므로 절대 수치에는 포화가 섞인다(§10.5 단서와 동일).
- 코호트 정리는 `cleanup_load.sql`이 `LT-%` 패턴으로 함께 처리한다(코호트 `booking_number` 프리픽스가 `LT-X`다).
- `IMAGE_TAG` 명시: §8.4와 동일.
- **좌석 확정 409 오분류는 로그와 토픽으로 관측한다(#489).** 대량 만료 창에서 결제가 들어오면 과금 후 좌석 미확정이 발생할 수 있는데, 이건 게이지에 안 잡힌다. 측정 후 booking 로그의 `[CRITICAL] 좌석 SOLD 확정이 409로` 건수와 `seat-confirm-failed-topic` 발행 건수로 그 창이 실제로 열렸는지 사후 확인한다. 만료 코호트만 시딩하는 이 시나리오에서는 결제가 없어 보통 0건이다 — 0이 아니면 다른 트래픽이 섞인 것이다.

### 11.8 재측정 — 릴레이 발행 상한 (#489)

§11.6의 부수 발견(발행 20/s < 생성 33.3/s)을 고쳐 `app.outbox.batch-size`를 100 → 300으로 올렸다. 그 변경이 실제로 적체를 해소하는지 **같은 B1 시나리오로 재측정**한다.

**판정 기준**

| 항목 | 이전 실측 (#345) | 목표 |
|---|---|---|
| 릴레이 발행률 피크 | 20.0/s | **≥ 33.3/s** (이론 상한 300/5s = 60/s) |
| `ticketrush_outbox_backlog` | 5,200까지 단조 증가 | tick마다 해소, **단조 증가 없음** |
| 좌석 적체 0 시점의 미발행 outbox | 5,186건 | 0 근방 |
| 좌석 해제 완료 ↔ outbox 소진 간격 | 8분 이상 | 측정해 기록(개선치) |

**A1/A2(청크 vs 단일 트랜잭션)는 반복하지 않는다.** #345에서 답이 나왔고 이번 변경과 무관하다. B1(만료 10,000건) 하나만 돌린다.

**사전 점검** — §11.4를 그대로 밟되 두 가지를 더 본다.

1. `SHOW INDEX FROM outbox`에 `idx_outbox_aggtype_status_id`가 있는지 확인한다. 릴레이 조회는 `INDEX()` 옵티마이저 힌트를 쓰는데, 이 힌트는 인덱스가 없어도 **경고 후 무시**되고 조용히 filesort로 degrade한다(#483). 없으면 먼저 적용하고 metadata에 기록한다.
   ```sql
   ALTER TABLE outbox ADD INDEX idx_outbox_aggtype_status_id (aggregate_type, status, outbox_id),
     ALGORITHM=INPLACE, LOCK=NONE;
   ```
2. **`batch-size: 300`이 실제로 물렸는지는 게이지가 증명한다.** actuator는 health·info·prometheus 3개만 노출해 `env`로 볼 수 없다(§11.4-3과 같은 제약). `docker exec seat-service env | grep APP_OUTBOX`가 비어 있는지(= yml 기본값 사용) 확인한 뒤, 측정 중 다음 둘로 확정한다.
   - `ticketrush_outbox_in_flight`가 **100을 넘는 표본이 한 번이라도** 잡히면 `batchSize > 100`이다
   - `rate(ticketrush_outbox_relay_total{result="success"}[1m])`가 **20/s를 넘으면** 확정이다

   (§11.2 "바인딩은 로그가 증명한다"와 같은 논법이다.)

**절차** — §11.5를 그대로 쓰고(`seed_expired_holds.sql`의 `@expired_count=10000`), 계측에 샘플러 하나를 추가한다.

```bash
DURATION=900 INTERVAL=5 ./outbox-sampler.sh > outbox-b1.csv   # load-test/bench/
```

이 CSV가 필요한 이유는 Prometheus 15초 스크랩이 in-flight 피크를 놓칠 수 있고(위 2번의 확정 근거가 거기 달려 있다), 리포트 표에 넣을 수치를 보존·결손과 무관한 원본에서 계산하기 위해서다. 적체 곡선 자체는 §11.6 PromQL로도 읽힌다.

**함께 확인할 트레이드오프**(이슈가 "측정으로 확인한다"고 적은 부분)

- **in-flight 슬롯 동작** — §10.3 판별표로 읽되 **임계값이 `batch-size`이므로 이제 300 기준**이다. in-flight가 0~300 사이에서 출렁이면 정상, 300에 붙어 정체하면 슬롯 고갈이다.
- **커넥션** — §11.5(c)의 HikariCP 1초 루프 그대로. 한 배치가 커졌으니 `active`가 이전(피크 1)보다 오르는지 본다.
- **프로듀서 버퍼** — 앱이 노출하면 `kafka_producer_buffer_available_bytes`로 본다. 없으면 발행 실패(`ticketrush_outbox_relay_total{result="fail"}`)가 0인지로 갈음하고, 추정을 수치로 쓰지 않는다.
- **⚠️ 폴링 소요시간 대 `lockAtMostFor="1m"`** — 이 상향에서 가장 위험한 축이다. `dispatch()`는 배치를 순차로 `send()` 하는데 그 호출이 `MAX_BLOCK_MS`(5초)까지 동기 블로킹될 수 있어(브로커 메타데이터·버퍼 대기), 최악 소요가 배치 크기에 비례해 3배가 된다. **`lockAtMostFor`를 넘겨 도는 폴링이 있으면 다른 인스턴스가 동시에 들어와 in-flight 가드가 무력해지고 중복 발행이 돌아온다**(`OutboxRelayService`의 `inFlight` javadoc이 자기고백한 한계). 릴레이 로그의 폴링 간 간격이 60초를 넘는 구간이 있는지 보고, `relay_total{result="success"}` 증가분과 실제 outbox 행 수를 대조해 증폭이 없는지 확인한다.
- **60/s는 이론 상한이지 보장값이 아니다.** `markSuccess`/`markFail`는 프로듀서 IO 스레드 하나에서 직렬로 `REQUIRES_NEW` 트랜잭션을 돈다(`OutboxStatusUpdater`). 배치가 3배면 그 스레드의 DB 왕복도 3배이고, 콜백이 다음 폴링(5초)보다 늦으면 그 폴링은 in-flight 때문에 0건을 발행한다 — 실효 발행률이 `batch-size / 5초`가 아니라 **콜백 처리율**에 묶인다. 측정값이 60/s에 못 미쳐도 33.3/s를 넘으면 이 이슈의 목표는 달성이며, 그 경우 상한이 어디서 걸렸는지 기록한다.

**릴레이 주기를 안 건드린 이유** — `OutboxRelayScheduler`의 `@SchedulerLock(lockAtLeastFor = "3s")`가 배치가 즉시 끝나도 락을 3초간 붙잡는다. `fixedDelay`를 1초로 낮춰도 실질 주기가 3초에 묶여 기대한 100/s가 아니라 33/s가 된다. 처리량은 주기가 아니라 `batch-size`로 올린다. 주기를 진짜로 낮추려면 `lockAtLeastFor`부터 재조정해야 하고, 그 값은 다중 인스턴스 failover 지연과 맞물린 별개 축이다.

증적은 `load-tests/k6/results/<YYMMDD>-489-relay-throughput/`에 남긴다(§11.5-6과 같은 구성).

## 12. 입장 검표 스파이크 측정 (#402)

공연 시작 직전 입장 게이트의 burst를 재현해 검표 경로(`POST /api/v1/entries/verify` → `/check-in`)의 처리량·p95/p99·에러율을 재고, **booking-service 동기 왕복이 검표 지연에 얼마나 기여하는지**와 **동일 QR 동시 스캔에서 정확히 1건만 통과하는지**를 확인한다.

시나리오: `load-test/scenarios/entry-spike.js`(스파이크), `entry-duplicate-scan.js`(정합성).
시드: `load-test/seed/seed_entry.sql`(리셋 내장).

### 12.1 이슈 서사와 실측의 차이 — 먼저 읽을 것

**(a) "verify 단독 vs check-in 포함"으로는 booking 왕복이 분리되지 않는다.** `EntryCheckInUseCase`도 `EntryVerifyUseCase`와 **같은 `verifyAndLoad()`를 거쳐 booking을 호출한다.** 두 경로의 차이는 왕복이 아니라 조건부 UPDATE 트랜잭션이다.

그래서 통제군을 **QR 발급(`GET /api/v1/ticket/bookings/{id}/qr`)**으로 잡았다. 이 경로는 게이트웨이 1홉 + JWT 처리 + ticket 단건 SELECT까지 verify와 동일하고, **booking 호출만 없다**(`TicketQrGetUseCase`는 로컬 데이터만 읽는다 — #364). 같은 iteration에서 연속 측정하므로 부하·호스트 상태도 같다.

```
Δp95 = entry_verify_duration_p95 − entry_qr_duration_p95   ← booking 왕복 비용(상한)
      entry_checkin_duration − entry_verify_duration        ← 조건부 UPDATE 비용
```

**(b) 클라이언트 관점 왕복 지연은 Prometheus로 잡히지 않는다.** `ticket-service/.../global/config/RestClientConfig.java`가 오토컨피그된 `RestClient.Builder` 빈이 아니라 **생 `RestClient.builder()`**로 만들어 `ObservationRegistry`가 붙지 않는다 → `http_client_requests_seconds`가 아예 없다. 따라서 왕복 비용은 위 (a)의 차분(상한: 네트워크·커넥션 획득·역직렬화 포함)과 §12.4의 booking-service **서버** 메트릭(하한: 순수 처리시간)으로 협공한다. 둘의 차이는 게이트웨이 홉 + 컨테이너 네트워크로 읽고, 단일 EC2 내부 통신이라 작아야 정상이다.

**#402 측정 당시** 서버 축에서 얻을 수 있는 것은 평균과 롤링 max뿐이었다 — 히스토그램 버킷이 없어 p95/p99를 서버에서 계산할 수 없었고, **그 회차의 퍼센타일 SSOT는 k6 클라이언트 측정이다.** #495에서 `slo` 버킷을 깔았으므로 **이후 회차에서는 서버 축 퍼센타일로 직접 교차검증한다**(§12.4). 다만 버킷 상한이 2s라 그 이상의 꼬리는 여전히 `_max`로 읽는다.

**(c) "Prometheus 스크랩 1분 간격"은 사실과 다르다.** `monitoring/prometheus.aws.yml`의 `scrape_interval`은 **15s**다. baseline·회복을 각각 5분 이상 유지하라는 요구는 비교 기준선 확보 목적으로 여전히 유효하므로 프로파일은 그대로 둔다.

**(d) 게이트웨이에 검표 라우트가 없었다.** `/api/v1/entries/**`는 ticket-service 라우트 predicate에 빠져 있어 **외부에서 도달 자체가 불가능**했다(인터넷에 열린 포트는 게이트웨이 8080뿐 — §7.1). `SecurityConfig`가 `hasRole("ADMIN")`을 걸고 `GatewayHeaderFilter`가 게이트웨이 주입 헤더로 인증하는 설계이므로 의도가 아니라 결함이다(#367 계열). #402에서 predicate에 추가했다 — **측정 전에 그 커밋이 배포본에 포함돼 있어야 한다**(§12.3-1의 스모크로 확인).

### 12.2 부하 모델 — 왜 `ramping-arrival-rate`인가

기존 시나리오는 전부 `config/options.js`의 `ramping-vus` 3단 stages를 썼다. 검표만 예외다.

`check-in`은 티켓을 `UNUSED → USED`로 **비가역** 소모한다. VU 수를 통제하면 소요 티켓 수가 응답 지연에 반비례해 변해서 시딩 규모를 미리 정할 수 없다(앱이 느려지면 덜 쓰고, 빨라지면 고갈된다). 도착률을 통제하면 소요량이 `stages`만으로 계산된다. 실제 입장 게이트의 부하도 스캐너 수가 아니라 **관객 도착률**이다.

| 구간 | 도착률 | 지속 | iterations | HTTP req/s |
|---|---|---|---|---|
| baseline | 10 /s | **6m** | 3,600 | 30 |
| 스파이크 램프업 | 10→60 /s | 20s | ~700 | 30→180 |
| 피크 유지 | 60 /s | 3m | 10,800 | **180** |
| 램프다운 | 60→10 /s | 20s | ~700 | 180→30 |
| 회복 유지 | 10 /s | **6m** | 3,600 | 30 |
| **합계** | | **15m 40s** | **≈19,400** | **≈58,200 req** |

- baseline·회복 6분 = 완료조건("최소 5분") + 램프 경계 오염분 여유 1분. 15s 스크랩 기준 24 포인트.
- iteration 1회 = 요청 3개(QR 발급 → verify → check-in)이고 그중 2건이 booking 홉을 포함하므로 **실효 부하는 req/s 수치보다 크다.** #344는 `POST /api/v1/booking` 258 RPS에서 호스트 CPU 99.87%였다. 그래서 피크를 의도적으로 낮게 잡았다.
- **첫 회차는 반드시 스모크로 포화점을 잡고 본 회차 값을 정한다**(§12.3-3). 캘리브레이션 노브는 `ENTRY_PEAK_RATE` 하나다.
- 필요 티켓 ≈ 19,400 → **25,000 시딩**(여유 29% + 중복 스캔 회차용 tail).

### 12.3 절차

```bash
SSH="ssh -i <key>.pem ubuntu@<EC2_IP>"
SQL() { $SSH "docker exec -i ticketrush-mysql sh -c 'mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" -N ticket_rush'"; }
```

**1. 재배포 + 라우트 반영 확인.** CD는 `push: main` + `workflow_dispatch`라 브랜치를 그대로 띄우려면 수동 디스패치한다.
```bash
gh workflow run cd.yml --ref test/402 && gh run watch
```
`actions/checkout@v7`이 디스패치된 ref를 체크아웃하고 `IMAGE_TAG=${{ github.sha }}`로 전 서비스를 굽는다. job에 `environment: production`이 걸려 있으므로 GitHub Environment의 "Deployment branches" 규칙이 main 전용이면 거부된다 — 그때는 규칙에 브랜치를 추가하거나 PR을 main까지 올린 뒤 측정한다.

라우트 스모크 — **404가 아니라 401**이 나와야 한다(라우트 존재 + JWT 필요):
```bash
curl -i -X POST https://<aws-gateway>/api/v1/entries/verify \
  -H 'Content-Type: application/json' -d '{"token":"x"}'
```

**2. 터널 + 시딩.** §7의 SSH 터널(3000·9090)을 먼저 띄운다. ADMIN 해시는 파일에 기본값이 없으므로 직접 만들어 주입한다.

```bash
# bcrypt 해시 생성 (평문은 저장소·증적에 남기지 않는다). 계정은 측정 후 cleanup 으로 지운다.
# rand -hex: 영숫자만 나와 셸·JSON 어디서도 이스케이프가 필요 없다.
# tr -d '\r\n': Windows Git Bash 에서 openssl·htpasswd 가 CRLF 를 내보낸다. \n 만 지우면 \r 이
#   평문 끝에 남아 해시에 섞이고, 시딩은 성공하는데 로그인만 401 로 죽는다. 실측에서 실제로 밟았다.
#   게다가 파일을 텍스트 모드로 다시 읽으면 \r 이 \n 으로 변환돼 눈으로는 차이가 보이지 않는다.
PW=$(openssl rand -hex 20 | tr -d '\r\n')
HASH=$(docker run --rm httpd:alpine htpasswd -bnBC 10 "" "$PW" | tr -d ':\r\n')

# 확인: 40 / 60 이 아니면 오염된 것이다.
echo "${#PW} ${#HASH}"
```

> ⚠️ **`--init-command` 으로 해시를 넘기지 않는다.** bcrypt 해시는 `$2a$10$...` 형태라 `$2`·`$10` 이
> 셸에서 위치 매개변수로 확장돼 **조용히 잘린 해시가 들어간다**(로그인만 실패하고 시딩은 성공한다).
> `$SSH "... 'mysql ...'"` 는 따옴표가 3중이라 이스케이프로 막기도 어렵다.
> 변수 SET 을 파일 앞에 붙여 **stdin 으로 흘려보낸다** — 해시가 데이터로만 지나가 확장되지 않는다.

```bash
printf "SET @i_confirm_loadtest_db=1, @ticket_count=25000, @admin_pw_hash='%s';\n" "$HASH" \
  | cat - load-test/seed/seed_entry.sql \
  | $SSH "docker exec -i ticketrush-mysql sh -c 'mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" ticket_rush'"
```
검증 쿼리에서 **`booking_id_min=1000001`, `contiguous=1`, `not_confirmed=0`, `unused=25000`, `owned_by_admin=25000`** 을 확인한다. 하나라도 어긋나면 진행하지 않는다. `booking_id_min`이 다르면 `-e ENTRY_BOOKING_ID_MIN`으로 맞춘다.

> `seed_entry.sql`은 `seed_load.sql`이 만든 LOADTEST 공연을 전제로 하고 **좌석은 건드리지 않는다**(검표 경로에 seat-service가 없다). 그래서 #344/#345 좌석 코호트와 간섭하지 않는 대신, 시드 상태는 '좌석은 AVAILABLE인데 예매는 CONFIRMED'라는 도메인상 불완전한 조합이 된다 — 측정 경로에 영향이 없음을 확인하고 의도적으로 남긴 것이며 리포트에도 적는다.

**3. 스모크 (수치 폐기용).** 포화점과 라벨을 확인하는 회차다.
```bash
docker compose run --rm --no-deps \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://<PROM_HOST>:9090/api/v1/write \
  k6 run \
  -e BASE_URL=https://<aws-gateway> \
  -e LOAD_ADMIN_PASSWORD='<평문>' \
  -e ENTRY_BASELINE_DURATION=1m -e ENTRY_PEAK_DURATION=30s -e ENTRY_PEAK_RATE=60 \
  //scripts/scenarios/entry-spike.js
```
(`-e` 위치 규칙·`--no-deps`·Git Bash `//scripts` 경로 치환은 §7.2·§10.2와 동일)

확인할 것: `entry_not_usable=0`, `entry_already_used=0`, `entry_ticket_exhausted=0`, `dropped_iterations=0`, 그리고 Prometheus에서 booking-service의 `uri` 라벨 실제 값(§12.4). 피크 CPU를 보고 `ENTRY_PEAK_RATE`를 확정한다.

**4. 본 측정.** 매 회차 앞에 `seed_entry.sql`을 다시 돌린다(리셋이 내장돼 있어 USED가 UNUSED로 되돌아간다). 측정 중 **Grafana 대시보드는 열어두지 않는다**(§7.2).

k6 종료 후 유입 축과 DB를 대조한다 — USED 수가 `entry_checkin_ok`의 ✓ 건수와 같아야 한다.
```bash
echo "SELECT ticket_status, COUNT(*) FROM ticket
       WHERE ticket_token_hash LIKE 'LOADTEST-ENTRY-%' GROUP BY ticket_status;" | SQL
```

> **이 시나리오의 측정 창 종점은 k6 종료 시각이다.** 검표 경로에는 outbox·컨슈머 같은 비동기 파이프라인이 없어 §10.2의 "PENDING=0 첫 관측" 규약이 적용되지 않는다. `metadata.txt`의 `WINDOW_END_SOURCE`에 그렇게 적는다.

회차는 2~3회 반복하고 회차 간 60초 이상 띄운다.

**5. 동일 QR 동시 다중 스캔 (3라운드).** 대상은 코호트 뒤쪽(스파이크가 도달하지 않는 구간)에서 고른다.
```bash
for BID in 1025000 1024999 1024998; do
  docker compose run --rm --no-deps \
    -e K6_PROMETHEUS_RW_SERVER_URL=http://<PROM_HOST>:9090/api/v1/write \
    k6 run -e BASE_URL=https://<aws-gateway> -e LOAD_ADMIN_PASSWORD='<평문>' \
    -e ENTRY_DUP_BOOKING_ID=$BID -e ENTRY_DUP_VUS=30 \
    //scripts/scenarios/entry-duplicate-scan.js
done
```
**권위 있는 판정은 k6가 아니라 SQL이다**(§10.2의 oversell 검증과 같은 선). 라운드마다:
```sql
-- 반드시 1행, USED, used_at NOT NULL
SELECT ticket_id, ticket_status, used_at FROM ticket WHERE booking_id = <BID>;
```
k6 요약의 `dup_checkin_ok ... ✓ 1 ✗ 29`(절대건수)를 함께 증적에 남긴다.

**6. 증적 + 정리.** `load-tests/k6/results/<YYMMDD>-402-entry-spike/`에 §10.5 구성대로 기록한 뒤:
```bash
$SSH "docker exec -i ticketrush-mysql sh -c 'mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" \
  --init-command=\"SET @i_confirm_loadtest_db=1\" ticket_rush'" < load-test/seed/cleanup_load.sql
```
**ADMIN 계정을 반드시 지운다.** `cleanup_load.sql`이 검표 코호트(ticket → booking 순)와 ADMIN 계정을 함께 처리한다. 이어 EC2를 중지한다(§7.3 온디맨드 전제).

### 12.4 PromQL

`job` 라벨은 MVC 앱 7개가 전부 `ticketrush-services`로 묶여 있다 — **서비스 구분은 `instance`다.**

> ℹ️ **서버 측 p95/p99는 #495부터 산출된다.** MVC 7개 서비스의 base `application.yml`에
> `management.metrics.distribution.slo.http.server.requests`로 버킷을 깔았다 —
> `1ms,2ms,5ms,10ms,25ms,50ms,100ms,250ms,500ms,1s,2s` + `+Inf`. `percentiles-histogram`은
> 켜지 않았다(Micrometer 사전정의 버킷이 깔려 `le` 수를 통제할 수 없다 — 앱 8개가 `mem_limit 384m`
> Prometheus 하나로 모인다, ADR 0007).
>
> 읽을 때 주의할 것 셋:
> - **`slo` 기반이라 `histogram_quantile()`은 경계 사이 선형보간이다.** 경계가 촘촘한 하단
>   (1~25ms)에서는 쓸 만하고, **2s를 넘는 지연은 `+Inf`에만 세어져 p99가 2s에서 포화된 것처럼 보인다.**
>   그 구간의 꼬리는 `_max`로 함께 읽는다.
> - **#402 이전 회차의 측정분에는 버킷이 없다.** 그 회차들의 퍼센타일 SSOT는 k6 클라이언트
>   측정(`k6_entry_*_p95/p99`)이고, 서버 축은 평균으로만 읽어야 한다.
> - **매 회차 시작 전에 버킷 존재를 확인하고 `metadata.txt`에 적는다.** 배포본 이미지가 #495 이전이면
>   여전히 0 시계열이고, `histogram_quantile()` 쿼리는 조용히 빈 결과를 낸다. 인스턴스 8개가 모두
>   나와야 하고, 값은 조합 수 × 12(`slo` 11 + `+Inf`)다.
>   ```promql
>   count by (instance) (http_server_requests_seconds_bucket)
>   ```
>   **MVC 서비스는 스모크 트래픽을 앱 포트로 흘린 뒤에 확인해야 한다** — 관리 포트(8090)로 오는
>   Prometheus 스크랩은 MVC의 `http_server_requests`에 잡히지 않는다(gateway는 WebFlux라 잡힌다).
>   요청이 한 번도 없던 서비스는 조합이 없어 버킷도 없다. 인증이 필요 없는 `/v3/api-docs`를 쓰면
>   도메인 상태를 건드리지 않고 조합을 만들 수 있다.
> - **`histogram_quantile()`이 `NaN`이면 데이터 부족이지 결함이 아니다.** 버킷 카운터가 rate 창에서
>   평평하면(요청이 몰아서 들어오고 멈춘 경우) 분모가 0이 되어 `NaN`이 나온다. 부하 중에는 문제되지
>   않지만, 스모크로 확인할 때는 rate 창을 채울 만큼 지속적으로 요청을 흘려야 한다.
> - **gateway(`job="gateway"`)의 p95는 엔드포인트별로 가를 수 없다.** Spring Cloud Gateway는
>   와일드카드 라우트에 `BEST_MATCHING_PATTERN`을 세팅하지 않아 `uri` 라벨이 `/**`·`UNKNOWN`으로
>   뭉개진다(#495 실측: gateway uri 카디널리티 **4** — `/**`, `UNKNOWN`, actuator 2개). 덕분에 버킷이
>   경로 수만큼 곱해지지 않아 켜는 데 문제는 없지만, **게이트웨이 축에서 얻는 값은 전 트래픽을
>   합친 p95다.** 엔드포인트별 서버 퍼센타일은 `job="ticketrush-services"` 쪽에서 본다.

```promql
# 검표 p95 / p99 (서버 관점, #495) — 네트워크·TLS·커넥션 수립이 빠진 순수 처리시간
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{
  instance="ticket-service:8090", uri=~"/api/v1/entries/.*"}[1m])))
histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket{
  instance="ticket-service:8090", uri=~"/api/v1/entries/.*"}[1m])))

# 2s 초과 비율 — p99가 2s에 붙어 보일 때 실제로 꼬리가 있는지 가른다
# le 표기는 실측으로 확정했다: 0.001 / 0.002 / 0.005 / 0.01 / 0.025 / 0.05 / 0.1 / 0.25 / 0.5 / 1.0 / 2.0 / +Inf
1 - (
  sum(rate(http_server_requests_seconds_bucket{instance="ticket-service:8090", le="2.0"}[1m]))
  / sum(rate(http_server_requests_seconds_count{instance="ticket-service:8090"}[1m]))
)

# 검표 평균 지연 (ticket-service 가 본 처리시간, 게이트웨이·네트워크 제외)
sum by (uri) (rate(http_server_requests_seconds_sum{
  instance="ticket-service:8090", uri=~"/api/v1/entries/.*"}[1m]))
/
sum by (uri) (rate(http_server_requests_seconds_count{
  instance="ticket-service:8090", uri=~"/api/v1/entries/.*"}[1m]))

# 검표 최댓값 (Micrometer 롤링 max — 꼬리 지연의 유일한 서버측 단서)
max by (uri) (http_server_requests_seconds_max{
  instance="ticket-service:8090", uri=~"/api/v1/entries/.*"})

# booking 내부 조회 평균 지연 (왕복 비용의 하한)
sum(rate(http_server_requests_seconds_sum{
  instance="booking-service:8090", uri="/api/v1/internal/booking/{bookingId}"}[1m]))
/
sum(rate(http_server_requests_seconds_count{
  instance="booking-service:8090", uri="/api/v1/internal/booking/{bookingId}"}[1m]))

# 내부 조회 호출량 — (verify RPS + check-in RPS) 와 1:1 이어야 한다(정합 검증)
sum(rate(http_server_requests_seconds_count{
  instance="booking-service:8090", uri="/api/v1/internal/booking/{bookingId}"}[1m]))

# 왕복 실패 — 재시도·서킷브레이커가 없어 곧바로 사용자 503(TICKET_503_001)이 된다
sum(rate(http_server_requests_seconds_count{
  instance="booking-service:8090", uri="/api/v1/internal/booking/{bookingId}",
  outcome!="SUCCESS"}[1m]))

# 왕복 기여분 곡선 (k6 클라이언트 관점, §12.1-(a) — 퍼센타일은 이쪽이 SSOT)
k6_entry_verify_duration_p95 - k6_entry_qr_duration_p95

# 엔드포인트별 유입 (tags:{name} 이 라벨로 실린다)
sum by (name, status) (rate(k6_http_reqs_total[1m]))

# 커넥션 풀 압박 — 메트릭명에 ticketrush_ 접두사가 붙지 않는다(Micrometer 기본)
hikaricp_connections_pending{instance=~"ticket-service:8090|booking-service:8090"}
```

> `uri` 라벨이 `/api/v1/internal/booking/{bookingId}` 템플릿으로 정규화되는지 **스모크 회차에서 눈으로 확인하고 metadata에 고정한다.** 검표 경로가 지금까지 도달 불가였던 탓에 이 시계열은 실측 전에는 존재하지 않는다(확인 시점의 booking-service `uri` 라벨은 `/api/v1/booking` 하나뿐이었다).

### 12.5 주의

- **티켓 인덱싱은 `exec.scenario.iterationInTest`다.** `booking-create.js`의 `__VU * 1000 + __ITER`을 쓰면 안 된다 — arrival-rate는 VU를 풀에서 재사용해 `(VU=2,ITER=0)`과 `(VU=1,ITER=1000)`이 충돌하고, 값이 희소·비단조라 필요 티켓 수를 계산할 수 없다. 인덱스가 겹치면 이미 USED인 티켓을 다시 쳐서 409가 나는데 **그게 '정상 차단'으로 위장되어 측정이 조용히 오염된다.** 단일 k6 인스턴스 전제이며, 이 토폴로지(ADR 0004)는 로컬 컨테이너 1개라 성립한다.
- **회차 무효 판정 기준.** 요약에서 `entry_not_usable > 0`(시드에 CONFIRMED 아닌 booking 혼입), `entry_already_used > 0`(인덱싱 결함으로 티켓 재사용), `entry_ticket_exhausted > 0`(티켓 고갈) 중 하나라도 걸리면 **그 회차의 수치를 쓰지 않는다.** 티켓 고갈 시 `exec.test.abort()`를 하지 않는 이유는 회복 구간 도중 abort하면 회차 전체를 버려야 하기 때문이다.
- **에러율 정의.** `http.setResponseCallback(http.expectedStatuses(200, 409))`로 409를 기대 응답에 넣어 `http_req_failed`에 5xx·401·400·404·타임아웃만 남긴다 = "정상경합 제외 에러율"(#348 기준과 동일). 409 두 종류는 상태코드로 못 가르므로 본문 `code`로 나눈다(`ApiResponse.code`는 최상위 String이라 전역 snake_case의 영향을 받지 않는다). 401(만료 QR)·404(티켓 없음)를 기대 응답에서 **뺀 것이 핵심**이다 — 이 시나리오에서 그것들은 전부 시딩/인덱싱 버그의 신호다.
- **`tags:{name}`은 선택이 아니라 필수다.** QR URL에 `bookingId`가 들어가서 태그를 안 주면 k6가 URL을 그대로 `name` 라벨로 써서 Prometheus에 티켓 수만큼 시계열이 생긴다.
- **QR은 iteration마다 새로 발급한다.** `ticket.qr.ttl-millis`가 5분인데 본 회차는 15분 40초다. `setup()`에서 미리 뽑으면 5분째부터 전 요청이 401 `TICKET_401_001`로 뒤집혀 스파이크 구간을 통째로 잃는다. 발급을 체인에 두면 TTL이 구조적으로 무관해지고 덤으로 §12.1-(a)의 통제군이 생긴다.
- **ADMIN 계정 해시는 커밋하지 않는다.** `seed_load.sql`의 MEMBER 해시는 저장소에 있지만, 검표 API는 ADMIN 권한만으로 남의 티켓을 입장 처리할 수 있어 위험도가 다르다. `seed_entry.sql`은 `@admin_pw_hash`가 없으면 가드에서 중단되고, 측정이 끝나면 `cleanup_load.sql`이 계정을 지운다. **측정 후 정리를 건너뛰면 인터넷에 열린 배포본에 관리자 계정이 상주한다.**
- **booking-service 지연/다운 주입은 이 회차에 하지 않는다.** 이슈의 (선택) 항목이지만 "왕복이 **끊겼을 때** 무슨 일이 나는가"는 다른 질문이고, 답은 코드로 이미 확정적이다 — `BookingRestClient`에 재시도·서킷브레이커가 없고 read-timeout이 10s라 booking이 느려지면 ticket-service 톰캣 스레드가 요청당 최대 10초 묶이고 곧 503 폭풍이 된다. 별도 측정 창(baseline+주입+회복)이 또 필요해 EC2 과금이 배가 되므로 **후속 이슈로 분리**하고, 이번에는 §12.4의 `outcome!="SUCCESS"` 곡선이 정상 부하에서 0인지 확인하는 데까지만 쓴다.
- 단일 EC2에 앱 9개 + Kafka·MySQL·Redis·관측 스택이 동거하므로 절대 수치에는 포화가 섞인다(§10.5·§11.7 단서와 동일).
- 시딩·정리 SQL의 오실행 가드(`@i_confirm_loadtest_db=1`)와 `IMAGE_TAG` 명시는 §8.4·§11과 동일하다.
