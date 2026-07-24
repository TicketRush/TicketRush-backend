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
