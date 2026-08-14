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

### 6.1 회차 간 재현성 — 과거 회차 수치를 그대로 잇지 않는다

**같은 코호트·같은 계단·같은 시나리오로 재도 회차가 다르면 값이 다르다.** 그 크기를 실측으로 알아 뒀으니, 개선분을 귀속할 때 이 오차보다 작은 차이는 신호로 읽지 않는다.

| 비교 | 좌석당 비용 | 고정항 | 조건 |
|---|---|---|---|
| [#529](https://github.com/TicketRush/TicketRush-backend/issues/529) A-pre vs [#403](https://github.com/TicketRush/TicketRush-backend/issues/403) | **+37%** | +8.5% | 같은 코호트·계단, **다른 이미지** |
| [#539](https://github.com/TicketRush/TicketRush-backend/issues/539)(#535 편승) vs #529 A-post | +3.3% | **+23.1%** | 같은 이미지, 샘플러 on/off |
| 같은 세션 안 A 콜드 vs 웜 | — | **8%** (2.236 → 2.058 ms) | 같은 회차, 워밍업 상태만 다름 |

**원인 중 배제된 것 하나**: `container-mem-sampler`([#515](https://github.com/TicketRush/TicketRush-backend/issues/515))는 아니다. 샘플러를 끈 상태의 좌석당 비용이 켜져 있던 #529와 3.3% 안에서 일치했다(#539 §8.2). **나머지(코드·호스트 상태)는 가르지 못했다** — #535는 이 한계를 문서화하는 것으로 닫혔다.

**그래서 지키는 규칙 넷.**

1. **성능 변경의 before는 새로 뜬다.** 과거 회차를 before로 빌리지 않는다. 런타임에 켜고 끌 수 있는 변경(인덱스 DDL 등)은 **before 회차 뒤에 적용**해 같은 이미지에서 짝을 만든다. 코드 변경이라 그럴 수 없으면(예: [#469](https://github.com/TicketRush/TicketRush-backend/issues/469) 캐싱 — 설정 플래그가 없어 배포 즉시 켜진다) 배포 전후로 가르고 **그 불확실성을 리포트 한계에 적는다.**
2. **두 점 적합(A·B)은 같은 열 상태에서 뜬다.** A를 워밍업 직후에 뜨면 콜드 성분이 A를 부풀려 기울기가 과소평가된다 — #539에서 1차 적합 0.335 µs가 열을 맞춘 재적합에서 0.409 µs로 **22% 달라졌다.**
3. **재시작 직후 회차는 첫 계단을 워밍업 전용으로 두고 그 수치를 처음부터 폐기한다.** #539의 2차 회차는 겹침 검산용으로 넣은 60 계단이 워밍업까지 겸하게 되어 검산 창이 절반으로 줄었다. CPU가 99.6%에서 90.0%로 내려오는 데 4분이 걸렸다.
4. **스모크로 정한 상한을 본 회차 상한으로 믿지 않는다.** 부팅 직후 스모크가 60 계단에서 CPU 95.67%·`dropped` 195·p95 4.84s를 냈는데, 워밍업 뒤 같은 계단이 CPU 90.5%·`dropped` 0·p95 0.54s로 돌았다(#539 §4.1). 스모크는 "시나리오가 도는가"만 보고, 상한은 워밍업 뒤에 다시 잡는다.

> **회차를 이어붙일 때는 판정 기준을 회차 *전에* 정해 `metadata.txt`에 적는다.** 사후에 정하면 자의적이다. 기준을 초과했는데도 이어붙이려면 **초과분의 정체를 규명한 근거**를 함께 남긴다(#539 §4.3 — 초과분이 워밍업임을 수렴 곡선으로 보이고 나서 이어 읽었다).

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

- **DEAD 전이**: `app.outbox.max-retries=3` 초과 시 `DEAD`가 되고 relay가 더는 집지 않는다. 정지 180초에서는 시도당 실패 판정에 delivery.timeout(120초)이 걸려 보통 1-2회 실패로 끝나지만, 정지를 길게 잡으면 나올 수 있다. `verify-loss.sql` ③에서 DEAD가 보이면 유실이 아니라 **"수동 개입 필요"로 분리 기록**하고, `status='PENDING'`으로 되돌려 소진을 재확인한다.
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
- 그 리스너의 컨슈머 스레드는 **`spring.kafka.listener.concurrency` 값만큼**이다(#596, 기본 3 = 토픽 파티션 수). `common/.../config/KafkaConfig.java`의 컨테이너 팩토리가 이 값을 읽는다. **회차마다 실제 값을 `metadata.txt`에 기록한다** — 환경변수 `SPRING_KAFKA_LISTENER_CONCURRENCY`로 재배포 없이 바뀌므로 이미지 태그만으로는 특정되지 않는다.
  - **#596 이전 회차(`260724-344`·`260729-504` 등)는 전부 스레드 1개 조건**이다. 그 회차들과 비교할 때는 이 차이를 교란 변수로 명시한다.
- Redisson `RLock`은 `(clientUUID:threadId)` 기준 **재진입** 락이다. 같은 스레드가 1,000건을 순차 처리하면 `tryLock`은 실패하지 않고 **재진입으로 성공**한다. 카운터는 `tryLock == false` 경로에서만 오른다(`SeatLockUseCase`).
  - 따라서 **concurrency 1에서는 이 카운터가 구조적으로 0**이었다. concurrency > 1이면 서로 다른 스레드가 같은 좌석을 집을 수 있어 **0이 아닌 값이 정상**이다. 다만 같은 파티션 안의 순차 처리와 `DefaultErrorHandler`의 in-place 재시도는 여전히 같은 스레드라 그 구간은 재진입이다.

**따라서 동시성 방어선은 락이 아니다.** 실제로 중복 선점을 막는 것은 둘이다.

1. `SeatHoldUseCase`의 `isAvailable()` 체크 → `ticketrush_seat_hold_total{result="unavailable"}`
2. `Seat.version` 낙관적 락(#427) → 커밋 시점 충돌 → 롤백 → Kafka 재시도로 수렴

**"차단율"은 §10.3의 `unavailable` 비율로 산출한다.** 락 경합률로 산출하면 항상 0%가 나와 방어선이 없다는 뜻으로 오독된다. 락은 정합성 장치가 아니라 경쟁을 앞단에서 걸러내는 **성능 최적화**이며, 정합성의 최종 방어선은 DB다 — 이 구분의 SSOT는 [ADR 0008](adr/0008-accept-redis-spof-with-fail-closed.md)과 `Seat.version` javadoc이다.

> 컨슈머 동시성이 1보다 크면(#596 이후 기본 3) 락 경합 수치는 달라진다. 그 조건에서 분기가 설계대로 도는지는 `SeatHoldConcurrencyTest`(seat-service)가 스레드를 갈라 검증해 둔 상태다. oversell 0 자체의 증명도 같은 테스트가 실 MySQL로 수행한다(Redis 락을 제거한 두 번째 케이스).
>
> `BookingCreatedEvent.key()`는 `bookingId`다. 파티션 키가 예매 단위이므로 **같은 좌석을 노리는 서로 다른 예매는 서로 다른 파티션에 흩어진다** — "파티션 순차 처리 덕분에 안전하다"는 서사는 성립한 적이 없다. concurrency 1에서 안전했던 이유는 순전히 컨슈머 스레드가 1개였기 때문이고, 그 전제는 #596으로 사라졌다. **지금 안전한 이유는 `Seat.version` 낙관적 락 하나다**(#427, ADR 0008).

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
>
> ⚠️ **Windows 에서 `bench/`·`chaos/` 스크립트를 EC2 로 흘려보낼 때는 CRLF 를 벗겨야 한다.** 워킹트리 파일이 CRLF 라(`core.autocrlf=true`) `ssh ... 'bash -s' < script.sh` 나 `cat > /tmp/x.sh` 로 넘기면 `set -euo pipefail\r` 이 되어 **`set: pipefail: invalid option name` 으로 첫 줄부터 죽는다.** 에러 메시지가 bash 버전 문제처럼 보여서 헤매기 쉽다(2026-07-27 #348 측정에서 샘플러가 두 번 이렇게 실패했다).
>
> ```bash
> tr -d '\r' < load-test/bench/outbox-sampler.sh \
>   | ssh -i <key> ubuntu@<EC2_IP> 'cat > /tmp/outbox-sampler.sh'
> ssh -i <key> ubuntu@<EC2_IP> 'DURATION=1200 INTERVAL=5 bash /tmp/outbox-sampler.sh' > samples.csv
> ```
>
> 넘긴 뒤 `bash -n /tmp/x.sh` 로 문법을 확인하면 실행 전에 걸러진다. `/bin/sh` 가 dash 인 것도 함께 주의한다 — 반드시 `bash` 로 실행한다.

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

   **(c) HikariCP 는 1초로 따로 떠야 한다.** Prometheus 스크랩이 15초라 2-6초짜리 tick 을 통째로 놓친다. actuator 를 직접 1초로 긁는다.
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
# 라벨은 application 이 아니라 instance 다(#489에서 확인 — application 라벨은 이 스택에 존재하지 않는다).
# 잘못 쓰면 Grafana 가 조용히 "No data" 를 낸다.
hikaricp_connections_pending{instance="seat-service:8090"}   # 커넥션 풀 압박 피크
hikaricp_connections_active{instance="seat-service:8090"}
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

- **in-flight 슬롯 동작** — §10.3 판별표로 읽되 **임계값이 `batch-size`이므로 이제 300 기준**이다. in-flight가 0-300 사이에서 출렁이면 정상, 300에 붙어 정체하면 슬롯 고갈이다.
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

회차는 2-3회 반복하고 회차 간 60초 이상 띄운다.

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
>   (1-25ms)에서는 쓸 만하고, **2s를 넘는 지연은 `+Inf`에만 세어져 p99가 2s에서 포화된 것처럼 보인다.**
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
- **booking-service 지연/다운 주입은 이 회차에 하지 않았고, #496에서 실측했다.** 이 회차(#402)에서 쓴 것은 §12.4의 `outcome!="SUCCESS"` 곡선이 정상 부하에서 0인지 확인하는 데까지다. **#496 실측 결과**(`load-tests/k6/results/260727-496-booking-outage/`)는 이 회차의 추론을 확인해 줬다 — 서킷 도입 전, `docker pause`로 booking을 120초 멈추자 **톰캣 스레드가 200/200으로 완전히 소진**됐고 verify 서버 평균이 4.27ms → **6,504ms**로 뛰었다. 더 중요한 것은 **QR 발급이 3,319건 전부(100%) 실패**했다는 점이다. QR은 #364에서 로컬화해 booking을 아예 호출하지 않는 경로이고 이 회차가 "왕복 없는 통제군"으로 쓴 엔드포인트인데, 스레드 풀을 공유하는 탓에 함께 무너졌다. 서킷 도입 후 같은 주입에서 톰캣 스레드 점유는 **4**, QR 실패는 **0건**이었다. 주입 회차의 절차·쿼리는 §12.6에 있다.
- 단일 EC2에 앱 9개 + Kafka·MySQL·Redis·관측 스택이 동거하므로 절대 수치에는 포화가 섞인다(§10.5·§11.7 단서와 동일).
- 시딩·정리 SQL의 오실행 가드(`@i_confirm_loadtest_db=1`)와 `IMAGE_TAG` 명시는 §8.4·§11과 동일하다.

### 12.6 booking 장애 주입 회차 (#496)

§12.1-12.5는 **booking이 정상일 때** 검표 경로가 버티는지를 쟀다. 이 절은 그 반대 — **booking이 죽거나 느려졌을 때 검표 경로가 함께 죽는가**를 잰다. #402가 (선택) 항목으로 남긴 질문이고, #496이 서킷브레이커·타임아웃 단축으로 답을 바꾸려 한 대상이다.

측정 대상 코드는 `ticket-service/.../out/apiclient/BookingRestClient.java`와 `.../global/config/BookingCircuitBreakerConfig.java`다. 서킷은 `booking` 인스턴스 하나이며 임계값은 실패율 50% / 느린호출 300ms 초과 50% / 윈도우 20(최소 10) / open 유지 10s / 반열림 3건이다.

**(a) 무엇을 비교하는가**

| | before | after |
|---|---|---|
| read-timeout | 10s | 1s |
| 서킷브레이커 | 없음 | `booking` |

before는 배포본을 되돌리지 않고 **환경변수 오버라이드로 재현**한다. 서킷은 코드에 박혀 있어 env로 끌 수 없으므로, before 회차는 `SERVICE_HTTP_READ_TIMEOUT_MS=10000`으로 타임아웃만 되돌리고 **서킷 임계에 닿기 전 구간(주입 직후 10-20초)** 을 비교 구간으로 쓴다. 두 축을 완전히 분리하려면 #402 배포본 이미지 태그(`91fa085…`)로 한 회차를 더 돌려야 하는데, 그 배포본에는 §12.4의 `slo` 버킷(#495)이 없어 서버 축 퍼센타일이 나오지 않는다 — **before/after를 같은 관측 해상도로 놓으려면 env 오버라이드 쪽이 맞다.** 이 선택을 metadata에 적는다.

**(b) 주입**

```bash
# EC2 배포 디렉토리에서. 원복은 스크립트의 trap 이 보장한다.
MODE=pause OUTAGE_SEC=120 ./booking-outage.sh   # 응답 없음 = read-timeout 경로
MODE=stop  OUTAGE_SEC=120 ./booking-outage.sh   # 연결 거부 = connect 실패 경로
```

`load-test/chaos/booking-outage.sh`. **두 모드를 모두 돌린다** — `stop`은 즉시 실패라 스레드를 묶지 않고, 이 이슈가 겨냥한 스레드 고갈은 `pause`에서만 재현된다. 주입 중에는 compose `up`/`restart`를 실행하지 않는다(`depends_on` 재기동이 걸린다).

부하는 `load-test/scenarios/entry-spike.js`를 그대로 쓴다. 구간은 **baseline → 주입 → 회복** 3분할이고, 주입 창은 부하 피크 구간 안에 들어가야 한다.

**(c) PromQL** (`instance` 축은 §12.4와 동일하게 `ticket-service:8090`)

```promql
# 서킷 상태 — 1 인 라벨이 현재 상태다. open 으로 전이한 시각과 closed 복귀 시각이 회복 시간이다
resilience4j_circuitbreaker_state{name="booking"}

# 서킷이 차단한 호출 수 (= booking 을 치지 않고 즉시 503 으로 떨어진 요청)
rate(resilience4j_circuitbreaker_not_permitted_calls_total{name="booking"}[1m])

# 서킷이 실패로 센 호출 / 실패율·느린호출율
rate(resilience4j_circuitbreaker_calls_seconds_count{name="booking",kind="failed"}[1m])
resilience4j_circuitbreaker_failure_rate{name="booking"}
resilience4j_circuitbreaker_slow_call_rate{name="booking"}

# 톰캣 스레드 점유 — 이 이슈의 핵심 지표. before 는 이 값이 max 로 붙고, after 는 붙지 않아야 한다
tomcat_threads_busy_threads{instance="ticket-service:8090"}
tomcat_threads_config_max_threads{instance="ticket-service:8090"}

# 검표 503 비율
sum(rate(http_server_requests_seconds_count{instance="ticket-service:8090",uri=~"/api/v1/entries.*",status="503"}[1m]))
  / sum(rate(http_server_requests_seconds_count{instance="ticket-service:8090",uri=~"/api/v1/entries.*"}[1m]))
```

> ⚠️ **`resilience4j_*` 시계열은 첫 호출 전에는 존재하지 않는다.** 서킷은 `create()`가 아니라 첫 `run()`에서 레지스트리에 등록되므로, 배포 직후 검표 트래픽이 한 번도 없었으면 쿼리가 조용히 빈 결과를 낸다. §12.4의 버킷 확인과 같은 선에서 **스모크로 한 번 흘린 뒤 시계열 존재를 확인하고 metadata에 적는다.** (#496 실측에서 그대로 밟았다 — 배포 직후 조회 시 시계열이 없었고 스모크 후 생성됐다.)
>
> ✅ **톰캣 스레드 메트릭은 #500부터 앱 기본값으로 켜져 있다.** MVC 7개 서비스의 `application.yml`에 `server.tomcat.mbeanregistry.enabled: true`가 들어갔으므로 `tomcat_threads_busy_threads` / `_config_max_threads` / `_current_threads`가 그냥 나온다. **override 적용은 더 이상 필요 없다.**
>
> 단, **#500 이전 이미지 태그로 before를 재현하는 경우**에는 여전히 비어 있다. Spring Boot 2.2부터 `server.tomcat.mbeanregistry.enabled` 기본값이 `false`라 Tomcat ThreadPool MBean이 등록되지 않고 Micrometer의 `tomcat.threads.*`가 통째로 빈다(#496 확인 시점에 `tomcat_sessions_*` 6개만 존재). 그때는 `load-test/chaos/ticket-tomcat-mbean.override.yml`로 켜고 **before/after 양쪽에 동일하게 적용한다.** 이 override는 컨테이너 재생성이라 **워밍업이 리셋되므로 적용 후 스모크부터 다시 시작한다.**

> 🚨 **서버 축(ticket-service)만 보면 이 장애가 보이지 않는다.** #496의 `stop` 회차에서 ticket-service의 `http_server_requests`에는 entries 요청이 **전부 200**으로만 잡혔고 평균 지연도 4.96ms로 평상시와 같았다. 503 시계열은 생성조차 되지 않았다. 톰캣이 accept를 거부하고 있었기 때문이며 — **연결이 거부된 요청은 서버 측 메트릭에 기록되지 않는다.** 같은 구간 gateway 축에서는 5xx가 전체의 41%였고 로그에 `Connection refused: ticket-service/...`가 남았다. **주입 회차의 판정은 반드시 gateway 축(`job="gateway"`, `outcome="SERVER_ERROR"`)과 k6 축을 함께 본다.** 서버 축만으로 판정하면 "문제 없음"이라는 정반대 결론이 나온다.

> ⚠️ **매 회차 앞에 `seed_entry.sql`을 다시 돌린다.** §12.1의 규칙이지만 주입 회차는 스모크·본 측정이 반복돼 빠뜨리기 쉽다. #496에서 실제로 한 회차를 폐기했다 — 스모크가 USED로 만든 4,399장을 본 회차가 재사용해 `entry_already_used=8798`(= 4,399 × 2)로 무효 판정에 걸렸다.

**(d) 회차 무효 판정**

§12.5의 기준(`entry_not_usable`·`entry_already_used`·`entry_ticket_exhausted`)이 그대로 적용된다. 여기에 하나 추가한다 — **주입 구간에서 `resilience4j_circuitbreaker_state{state="open"}`이 한 번도 1이 되지 않으면 after 회차는 무효다.** 서킷이 열리지 않았다는 것은 주입이 서킷 임계에 닿지 못했다는 뜻이고(주입 시간이 짧거나 도착률이 낮음), 그 회차로는 "완화됐다"를 말할 수 없다.

**(e) 기록할 값**

`metadata.txt`에 before/after 각각: 검표 503 비율, `tomcat_threads_busy` 피크와 max 대비 비율, 서킷 open 전이 시각·closed 복귀 시각(= 복구 시간), 주입 시작·종료 UTC, 그리고 (a)에서 고른 before 재현 방식.

**(e-2) #496 실측 결과 (2026-07-27)**

| 지표 (`pause` 120초 주입) | before | after |
|---|---|---|
| `tomcat_threads_busy` 피크 | **200 / 200** | **4** |
| QR 발급 실패(booking 미호출 경로) | **3,319건 (100%)** | **0건** |
| verify 서버 평균 피크 | 6,504.73ms | 180.13ms |
| `dropped_iterations` | 2,105 | 76 |
| 서킷 검출 / 복구 | — | 10초 / ~10초 |

`stop` 회차는 QR 최대 지연이 **30.22s → 503ms**였다. 상세는 `load-tests/k6/results/260727-496-booking-outage/report.md`.

**(f) 폴백 정책 — 측정으로 바뀌지 않는 것**

서킷 open 구간의 검표 요청은 **전부 503(`TICKET_503_001`)이 되는 것이 정상이다.** 검표는 권위 있는 `bookingStatus` 확인이 목적이라(#364) 조회 불가 상태에서 입장을 통과시킬 수 없다 — 통과시키면 환불 진행 중(REFUNDING)인 예매가 그대로 입장한다. 따라서 이 회차의 성공 기준은 "503이 줄었다"가 아니라 **"503이 검표 경로에 갇혔고, 스레드 고갈로 번지지 않았다"** 이다. 가용성을 잃되 정합성을 지키는 이 비대칭은 Redis SPOF에 대한 [ADR 0008](adr/0008-accept-redis-spof-with-fail-closed.md)의 fail-closed와 같은 선택이다.

## 13. 오픈런 스파이크 e2e 종합 부하 측정 (#348)

개별 핫패스 측정(§10~§12)과 달리 **사용자 여정 전체를 게이트웨이를 통해** 부하 걸어 현재 구성의 최대 처리량(포화점)과 1차 병목을 찾는다. 시나리오는 `load-test/scenarios/openrun-e2e.js`.

### 13.1 이슈 서사와 실측의 차이 — 먼저 읽을 것

이슈 #348 본문의 여정은 `로그인 → 공연 조회 → 좌석맵 조회 → 좌석 선점(HOLD) → 예매 생성 → 결제 → 티켓 발급`이지만, **코드로 확인한 결과 이 중 셋이 실제와 다르다.**

**(a) 좌석 선점(HOLD)은 HTTP API가 아니다 — 여정 순서가 역순이다**

`SeatController`는 GET만 있고 HOLD를 여는 공개 엔드포인트가 없다. 실제 경로는 §10.1이 서술한 그대로다.

```
POST /api/v1/booking → booking + outbox 한 트랜잭션 커밋
  → OutboxRelayScheduler (fixedDelay 5s, batch-size 300)
    → booking-created-topic
      → seat-service BookingCreatedEventListener (groupId=seat-group, concurrency 1)
        → SeatFacade.tryLockSeat
```

즉 **예매 생성이 좌석 선점을 유발한다.** k6가 칠 수 있는 것은 예매 생성까지이고, HOLD는 유입 축이 아니라 처리 축(`ticketrush_outbox_backlog` + consumer lag)에서 본다. 유입 RPS와 HOLD 처리율은 애초에 같은 값이 될 수 없다.

**(b) 결제·티켓 발급은 이 구성에서 실행할 수 없다**

`StubPaymentApprovalClient`는 `@Profile("!prod")`이고, javadoc이 "운영(prod) 프로파일에서는 환경변수 설정과 무관하게 절대 활성화되지 않는다"고 못박는다. [ADR 0004](adr/0004-load-test-execution-topology.md)가 정한 측정 대상은 `SPRING_PROFILES_ACTIVE=prod` 단독 배포본이므로 **`PAYMENT_PG_STUB_ENABLED=true`를 줘도 stub은 뜨지 않는다.**

남는 것은 실 Toss 호출뿐인데 `paymentKey`는 PG가 발급하는 값이라 k6가 만들 수 없고, 웹훅(`POST /api/v1/payment/webhook`)도 paymentKey로 PG에 재조회해 진위를 검증한다. **우회 경로가 없다.**

→ **여정은 예매 생성까지로 한정한다.** 이슈 완료조건의 "예매확정→티켓발급 파이프라인 backlog 회복시간"은 이 회차에서 측정 불가이며, 리포트에 사유와 함께 남긴다. prod에서 stub을 허용하는 변경은 #348 범위 밖이다(보안 판단이 필요한 별건).

**(c) 목표 SLO는 이 하드웨어에서 달성 불가능하다 — 포화점이 앱이 아니라 호스트에서 온다**

[ADR 0007](adr/0007-observability-stack-colocation.md)의 배치는 **단일 EC2 `m7i-flex.large`(2 vCPU / 7.6 GiB)** 에 앱 8개 + MySQL·Redis·Kafka·Prometheus·Grafana다. 이미 실측된 값이 둘 있다.

| 회차 | 부하 | 호스트 CPU |
|---|---|---|
| #344 | `POST /api/v1/booking` 단독 258 RPS | **99.87%** (§10.5) |
| #402 | 검표 180 req/s | 73.93% (스모크 피크 97.91%) |

이슈가 세운 "조회 경로 목표 1,000+ RPS"는 이 구성에서 성립하지 않는다. 본문도 "실측으로 검증·조정"이라 열어뒀으므로, **결과는 SLO 달성 여부가 아니라 "현재 구성의 최대치와 그 상한을 만드는 자원"으로 읽는다.** 후속 이슈(#469 좌석 조회 캐싱, #470 대기열, #423 Rate Limit)의 근거가 이 수치다.

**(d) 구조적 상한 3개 — 앱을 아무리 밀어도 넘지 못한다**

| 상한 | 값 | 근거 |
|---|---|---|
| outbox 릴레이 실효 발행률 | 중위 **53.2/s** (이론 상한 60/s) | §11.8 (#489) |
| Kafka 소비 병렬도 | **1** (파티션은 3인데도) | `setConcurrency` 부재 — §10.1 |
| Nginx 리버스 프록시 | Ubuntu 기본값, 튜닝·전용 지표 없음 | `docs/production-domain-https.md` §7 |

Nginx는 부하 경로에 있으면서(443 → `127.0.0.1:8080`) 자체 지표가 없다. node-exporter의 호스트 CPU로만 간접 관측되므로 **"게이트웨이 앞단이 상한인지"는 이 회차로 가릴 수 없다.** 리포트 §한계에 명시한다.

**(e) 예매가 5-6분 뒤 만료되면서 배경 부하를 만든다 — 유입의 2배가 outbox로 간다**

`BookingExpireUseCase`의 `PAYMENT_WAIT_MINUTES = 5`, `BookingExpirationScheduler`는 `@Scheduled(fixedDelay = 60000)`이다. **이 회차는 결제를 하지 않으므로(§13.1-(b)) 생성한 예매가 예외 없이 5-6분 뒤 EXPIRED로 전이**하며, booking당 `BookingExpiredEvent`를 outbox에 한 행 더 쓴다. 좌석도 `SeatHoldExpiredEvent` 경로로 풀린다.

계단식 회차는 20m30s 동안 9,210건을 만드는데 **마지막 5-6분 치를 뺀 대부분이 측정 창 안에서 만료된다.** 그 결과:

- outbox로 나가는 이벤트 총량이 생성분의 **사실상 2배** — (d)가 상한으로 제시한 릴레이 53.2/s에 대한 유효 여유가 절반이 된다
- `ticketrush_outbox_backlog`를 병목 신호로 읽을 때 **생성 트래픽과 만료 트래픽이 섞인다**
- (a)가 그린 `booking → seat` 단일 경로 외에 만료 계열이 함께 돌아, 처리 축이 그림보다 복잡하다

**이것은 결함이 아니라 이 회차의 조건이다.** 실제 오픈런에서도 결제하지 않은 예매는 만료되므로 현실적이기까지 하다. 다만 **수치를 해석할 때 반드시 감안해야 하고**, 측정 창의 종점도 만료 계열까지 드레인된 시점으로 잡는다(§13.4-(5)).

**(f) ⚠️ 조회 축은 앱이 아니라 응답 크기에서 막힌다 — 2026-07-27 실측**

첫 스모크에서 **좌석맵 조회의 서버 처리 시간이 81.96ms 인데 클라이언트 p95 는 34.97초**로 나왔다. 400배 차이이고, 같은 구간 호스트 CPU 는 58.86%(피크 82.83%)에 gateway 5xx 는 0건이었다. **앱이 느린 게 아니라 응답을 전달하는 데 걸린 시간이다.**

| | 값 |
|---|---|
| 좌석맵 응답(공연당 2,080석) | **221KB** (좌석당 107.6 bytes) |
| 조회 여정 1회 중 좌석맵 비중 | **99.05%** (목록 1,775B + 상세 367B + 좌석맵 223,720B) |
| gzip | 측정 당시 **미적용** — `Accept-Encoding: gzip` 을 보내도 크기 동일, `Content-Encoding` 없음 |
| gzip level 6 적용 시 | **9,709B (95.66% 절감)** |

원인은 두 겹이었다. Spring Boot 의 `server.compression.enabled` 기본값이 false 이고 어느 서비스 yml 에도 설정이 없으며, 호스트 Nginx 에도 gzip 지시어가 없다(`docs/production-domain-https.md` §7).

> **✅ 해소됨 — `#505`.** seat-service 의 `application.yml` 에 `server.compression.enabled: true` 를 켰다(앱 origin 압축, 선택 근거는 `load-tests/k6/results/260727-348-openrun-e2e/report.md` §3.4). 로컬 실측으로 `Content-Encoding: gzip` 과 11.8배 축소를 확인했다(9,771B → 830B).
>
> **`min-response-size`(기본 2KB)가 걸러 줄 것이라는 예상은 틀렸다.** Tomcat 의 `CompressionConfig` 는 `contentLength != -1 && contentLength < min` 일 때만 제외하는데, MVC 컨트롤러 응답은 `Transfer-Encoding: chunked` 라 길이가 -1 이라서 크기 판정을 건너뛴다. 결과적으로 **seat-service 의 작은 JSON 응답도 함께 압축된다**(실측: `seat-counts` 199B → 185B). 길이를 알리는 actuator 응답(49B)은 예상대로 제외됐다. 압축 CPU 를 측정할 때 "좌석맵 하나만 압축된다"고 가정하지 말 것.
>
> **아래 캘리브레이션 값(조회 ≤ 10 iter/s)은 221KB 응답 기준이라 압축 적용 후에는 무의미하다** — 스모크로 새 무릎을 다시 잡아야 한다.

**측정 관점에서 이것이 뜻하는 바:**

- 조회 도착률을 계속 올리면 **앱이 아니라 k6 를 돌리는 회선의 다운로드 대역을 재게 된다.** 실측 수신은 4.5 MB/s 였고, 221KB 응답 기준 약 20 iter/s 가 상한이다.
- 그래서 이 회차는 **조회 축을 회선이 감당하는 범위(≤ 10 iter/s ≈ 2.2 MB/s)로 묶어 배경 부하로 두고, 응답이 작아 회선 영향을 받지 않는 예매 축을 계단으로 올려** 앱의 포화점을 잰다.
- 판정은 **`k6_http_req_waiting_p95`(TTFB) 대비 `k6_http_req_receiving_p95`(본문 수신)** 로 한다. receiving 이 지배적이면 전송이 병목이다(§13.6).

**동시에 이것은 측정 환경만의 문제가 아니다.** 실제 사용자도 오픈런 순간 모바일 회선으로 같은 221KB 를 받는다. 서버가 82ms 에 응답을 만들어도 화면은 그만큼 늦게 뜬다. `#469`(좌석 조회 Redis 캐싱)는 **서버 조회 부하를 줄일 뿐 응답 크기는 그대로**이므로 이 축과는 별개다.

### 13.2 부하 모델 — 조회 축과 예매 축을 나눈다

예매는 iteration당 좌석 1개를 **비가역 소모**한다. 여정 전체를 같은 도착률로 돌리면 좌석 수가 곧 부하 총량의 상한이 되어 계단을 끝까지 올릴 수 없다. 그래서 scenario를 둘로 나누고 예매 축만 `E2E_PURCHASE_RATIO`(기본 0.2)로 낮춘다. 실제 티켓팅도 조회:예매가 크게 기운다.

| scenario | 여정 | 좌석 소모 | executor |
|---|---|---|---|
| `browse` | 공연 목록 → 인기 공연 상세 → 인기 공연 좌석맵 | 없음 | `ramping-arrival-rate` |
| `purchase` | 예매 생성 | iteration당 1석 | `ramping-arrival-rate` |

`ramping-arrival-rate`를 쓰는 이유는 §12.2와 같다 — VU 수를 통제하면 소요 좌석이 응답 지연에 반비례해 변해서 시딩 규모를 미리 정할 수 없다.

**좌석 배정.** `setup()`이 대상 공연들의 좌석맵을 한 번씩 읽어 AVAILABLE 좌석의 `(perfId, min, step, count)`를 만들고, iteration 인덱스를 거기에 누적 매핑한다(`seatId = min + step × offset`). 배열 전체를 setup 데이터로 넘기지 않는 것은 k6가 그 값을 **VU마다 복사**하기 때문이다.

> ⚠️ **seat_id는 공연 안에서 연속이 아니라 등차다.** `seed_load.sql`의 `seat_layout CROSS JOIN r CROSS JOIN c`를 MySQL이 `(r, c)` 바깥 루프로 실행해 **공연들이 인터리브로 삽입**되기 때문이고, 그래서 step이 보통 공연 수와 같다. 로컬 6,120석(공연 10건) 실측에서 `LOADTEST-000001`의 seat_id는 `121, 131, 141, …, 6111`로 `distinct_steps=1, step=10`이었다.
>
> 간격을 가정하지 않고 **응답에서 읽어 검증**한다 — 간격이 일정하지 않으면 중간 좌석을 다른 코호트가 점유한 것이므로 setup에서 중단한다.

좌석 경합 자체는 §10(#344)이 이미 쟀다. 이 회차는 포화점이 목적이므로 **유일 배정으로 409를 배제하고 순수 처리량을 본다.** 그래도 409가 나오면 리셋 누락이거나 다른 코호트와 겹친 것이다.

**필요 좌석 = Σ(rate × ratio × stage 길이)**, 전환 램프 구간 포함. 기본값 기준(`k6 inspect`로 확정):

| 회차 | 프로파일 | 총 길이 | 필요 좌석 |
|---|---|---|---|
| 계단식 | `10,20,40,80` × 5분 | 20m30s | **9,210** |
| 스파이크 | baseline 10 → peak 80 (6m/5m/6m) | 17m20s | **6,420** |

두 회차를 이어 돌리면 15,630석이 필요하지만, **회차 사이에 `reset_e2e.sql`을 돌리므로 한 회차 최대치(9,210석)만 확보하면 된다.**

### 13.3 시딩 — 좌석 규모와 공연 ID 확인

`seed_load.sql`을 규모만 바꿔 재사용한다(전용 시드 파일 없음). **공연당 좌석은 실제 공연장 규모(2천석대)로 두고 공연 수로 총량을 채운다.** 좌석맵 조회는 페이지네이션 없이 전 좌석을 반환하므로, 공연 하나에 2만석을 몰면 응답이 수 MB가 되어 가정용 회선이 병목이 된다([ADR 0004](adr/0004-load-test-execution-topology.md) §한계).

```sql
-- seed_load.sql 상단 @vars
SET @perf_count  = 10;   -- 공연 수
SET @rows_per    = 26;   -- 'A'~'Z' 라 26 이하
SET @cols_per    = 80;   -- 공연당 2,080석 -> 총 20,800석
```

시딩 후 **실제 공연 ID와 좌석 범위를 확인해 `-e`로 넘긴다.** `seed_load.sql`은 seat_id를 AUTO_INCREMENT에 맡기므로 실행마다 값이 다르다.

```bash
SSH="ssh -i <key>.pem ubuntu@<EC2_IP>"
SQL() { $SSH "docker exec -i ticketrush-mysql sh -c 'mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" -N ticket_rush'"; }

# 공연 ID 목록 + 공연별 AVAILABLE 좌석 규모·간격
# reset_e2e.sql 의 두 번째 검증 쿼리가 같은 값을 내므로, 리셋을 돌렸다면 그 출력을 그대로 쓴다.
echo "SELECT performance_id, COUNT(DISTINCT gap) AS distinct_steps, MIN(gap) AS step
        FROM (SELECT s.performance_id,
                     s.seat_id - LAG(s.seat_id) OVER (PARTITION BY s.performance_id
                                                      ORDER BY s.seat_id) AS gap
                FROM seat s JOIN performance p ON p.performance_id = s.performance_id
                                              AND p.title LIKE 'LOADTEST-%'
               WHERE s.seat_status='AVAILABLE') x
       WHERE gap IS NOT NULL GROUP BY performance_id;" | SQL
```

- `PERF_ID` = 조회 축이 집중할 **인기 공연 1건**(핫 로우 재현)
- `E2E_PURCHASE_PERF_IDS` = 예매 대상 공연 ID 목록(콤마 구분). 필요 좌석을 채울 만큼 넣는다
- **`distinct_steps`가 1이 아닌 공연은 예매 대상에서 뺀다** — setup이 어차피 거부한다

> ⚠️ **검표 코호트와 좌석이 겹친다.** `seed_entry.sql`(#402)은 LOADTEST **첫 공연의 MIN seat_id**에 booking 25,000건을 심는다(좌석 상태는 AVAILABLE로 남긴다). e2e가 같은 좌석을 예매해도 앱은 seat 상태만 보므로 통과하지만, 두 코호트를 함께 쓸 때는 **`seed_entry.sql`을 먼저 돌리고 그 공연을 `E2E_PURCHASE_PERF_IDS`에서 빼는 것**이 깔끔하다.

### 13.4 절차 (회차마다 반복)

토폴로지·사전조건은 §7과 같다 — k6는 로컬, 대상은 AWS 배포본, SSH 터널 선행. `IMAGE_TAG` 확인(§8.4)도 그대로다.

**(1) 리셋 — DB와 Redis를 함께 되돌린다**

```bash
# DB: 앱이 만든 booking/HOLD 만 되돌린다(시드 코호트 LT-* 는 보존)
$SSH "docker exec -i ticketrush-mysql sh -c \
  'mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" --init-command=\"SET @i_confirm_loadtest_db=1\" ticket_rush'" \
  < load-test/seed/reset_e2e.sql
```

`leftover_app_bookings = 0`과 예매 대상 공연의 `distinct_steps = 1`을 눈으로 확인한다.

`non_available_seats`는 **0이 아닐 수 있고, 그것이 정상이다.** `reset_e2e.sql`이 일부러 보존하는 시드 코호트가 좌석을 잡고 있기 때문인데, 그 주체는 `seed_load.sql`의 `@booking_pct` 코호트(`LT-`)와 `seed_expired_holds.sql`의 `LT-X` 코호트다. **검표 코호트(`seed_entry.sql`)는 여기 해당하지 않는다** — 좌석을 SOLD로 전이시키면 #344/#345 코호트를 오염시키므로 일부러 AVAILABLE로 남긴다(`seed_entry.sql:120-124`).

```bash
# Redis: 좌석 락 키는 성공 경로에 unlock 이 없어 TTL 5분간 살아남는다. DB만 되돌리면
#        다음 회차의 홀드가 락 획득에 실패해 전부 보상 처리되고 측정이 무의미해진다(§10.2).
#        좌석이 수만 개라 단일 DEL 이 아니라 스캔으로 지운다. KEYS 는 쓰지 않는다(블로킹).
#
# ⚠ `-a <비밀번호>` 를 쓰지 않는다. redis 컨테이너에 있는 것은 REDISCLI_AUTH 뿐이고
#   ($REDIS_PASSWORD 는 호스트 .env 변수라 컨테이너 안에서 빈 문자열로 펼쳐진다),
#   redis-cli 가 REDISCLI_AUTH 를 자동으로 읽는다(deploy/docker-compose.prod.yml 의 redis 주석).
#   §10.2 의 기존 관용구와 같은 형태다. -a 를 붙이면 인증이 깨져 키가 하나도 안 지워지는데,
#   그 실패는 조용해서 다음 회차가 통째로 무효가 된다.
$SSH "docker exec ticketrush-redis sh -c \
  'redis-cli --scan --pattern \"seat:lock:*\" | xargs -r redis-cli DEL'"

# 지워졌는지 반드시 확인한다 — 0 이어야 한다.
$SSH "docker exec ticketrush-redis sh -c \
  'redis-cli --scan --pattern \"seat:lock:*\" | wc -l'"
```

**(2) 스모크 회차 — 캘리브레이션 (수치는 폐기)**

본 회차의 피크를 실측으로 정한다. #402가 스모크에서 호스트 CPU 97.91%를 보고 피크를 60/s로 확정한 것과 같은 절차다. 짧게 돌려 **무릎이 어디인지**만 본다.

```bash
docker compose run --rm --no-deps \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://<PROM_HOST>:9090/api/v1/write \
  k6 run \
  -e BASE_URL=https://<aws-gateway> \
  -e LOAD_USER_PASSWORD='<평문>' \
  -e PERF_ID=<인기공연> -e E2E_PURCHASE_PERF_IDS=<id,id,...> \
  -e E2E_STAGE_RATES=10,30,60 -e E2E_STAGE_DURATION=1m \
  //scripts/scenarios/openrun-e2e.js
```

**(3) 계단식 본 회차 — 포화점 탐색**

각 단계를 **최소 5분 유지**한다. Prometheus 스크랩이 15초라 단계당 20표본이 확보되고, 이슈 완료조건("각 부하 단계가 최소 5분 유지된 상태의 값으로 포화점 판정")도 이 조건이다.

```bash
docker compose run --rm --no-deps \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://<PROM_HOST>:9090/api/v1/write \
  k6 run \
  -e BASE_URL=https://<aws-gateway> -e LOAD_USER_PASSWORD='<평문>' \
  -e PERF_ID=<인기공연> -e E2E_PURCHASE_PERF_IDS=<id,id,...> \
  -e E2E_PROFILE=ramp -e E2E_STAGE_RATES=<스모크로 정한 계단> -e E2E_STAGE_DURATION=5m \
  //scripts/scenarios/openrun-e2e.js
```

**(4) 리셋 후 오픈런 스파이크 회차**

(1)을 다시 돌린 뒤 프로파일만 바꾼다. 오픈런은 "수 초 내 급증"이므로 램프가 10초다(검표 스파이크의 20초보다 짧다).

```bash
  -e E2E_PROFILE=spike -e E2E_BASELINE_RATE=<baseline> -e E2E_PEAK_RATE=<피크> \
  -e E2E_BASELINE_DURATION=6m -e E2E_PEAK_DURATION=5m
```

**(5) 릴레이 소진 대기 — 측정 창의 종점**

k6 종료 시점에는 outbox에 미발행분이 남아 있다. **PENDING이 0이 된 뒤**에 검증해야 "처리가 끝난 상태"의 수치를 본다. 이 시각이 측정 창의 종점이므로 UTC로 기록한다(§10.2·metadata의 `WINDOW_END_SOURCE` 규약).

**생성 계열만 기다리면 안 된다.** §13.1-(e)대로 예매가 5-6분 뒤 만료되면서 만료 계열 이벤트가 뒤따라 나온다. `BookingCreatedEvent`만 0을 확인하고 종점을 찍으면 **만료 계열이 아직 드레인 중인 시점을 "처리가 끝난 상태"로 기록**하게 된다.

```bash
echo "SELECT event_type, status, COUNT(*) FROM outbox
       WHERE event_type IN ('BookingCreatedEvent','SeatHoldFailedEvent',
                            'SeatHoldExpiredEvent','BookingExpiredEvent')
       GROUP BY event_type, status ORDER BY event_type, status;" | SQL
```

네 계열 전부 `PENDING`이 0이어야 한다. 마지막 예매가 만료되기까지 k6 종료 후 **최대 6분 + 릴레이 주기**를 기다려야 하므로, 종점은 그보다 늦다.

**(6) 검표 스모크 — #500에서 이관된 완료조건**

#496이 `slowCallDurationThreshold`를 300ms → 500ms로 완화한 것이 2026-07-27 배포(`d5571697`)로 처음 나갔는데, **그 오탐은 배포 후 첫 검표 트래픽에서만 드러난다.** 검표 경로가 콜드로 남아 있는 한 판정 조건이 유지되므로 이 회차의 스모크가 그 첫 트래픽이 된다.

`seed_entry.sql` 시딩 후 `entry-spike.js`를 스모크 규모로 1회 돌리고, **`resilience4j_circuitbreaker_not_permitted_calls_total` 증가분이 0인지** 확인한다. 판정 기준은 `slidingWindowSize=20` · `minimumNumberOfCalls=10`이라 검표 요청 10건이면 평가된다. 결과는 #348과 #500 양쪽 완료조건에 기록한다.

**(7) 정합성 검증 — oversell 0**

> **⚠️ (a) 는 회차 형태에 따라 갈린다. 상태 필터(`booking_status IN ('PENDING','CONFIRMED')`)는 쓰지 않는다.**
>
> 예매는 5-6분이면 만료되므로(§13.1-(e)) **20분짜리 회차의 종료 시점에는 활성 예매가 0건**이다. 상태로 거르면 쿼리가 아무 행도 보지 않은 채 "0행 = 정상" 을 낸다 — 검출력이 0 이다. #554 가 회차 중에 발견해 상태 무관 검사로 바꿔 돌았다(report §9.3).
>
> **그리고 상태 무관 검사도 아무 회차에나 쓸 수 없다.** #598 이 #554 의 검사를 #344 형 회차에 그대로 적용해 **4,674행을 오검출**했다 — 버그가 아니라 쿼리가 시나리오에 안 맞았다. 아래 (a1)/(a2) 중 회차 형태에 맞는 것을 고른다. **판정 기준(오버셀 0)은 바뀌지 않는다. 바뀌는 것은 검출 도구다.**
>
> **SQL 원문을 `oversell-<arm>.txt` 에 함께 남긴다.** #554 는 출력만 남겨 재현이 불가능했고 #598 이 그것을 지적했다.

```bash
# (a1) VU 마다 좌석이 유일한 회차 (openrun-e2e · waiting-room 계열).
#      예매 1건 = 좌석 1석이 전제이므로 '같은 좌석을 가리키는 예매 2건' 이 곧 oversell 이다.
#      대상 공연으로 좁힌다 — p.title LIKE 'LOADTEST-%' 는 과거 회차 잔재까지 긁는다
#      (#598 회차 전 실측: LOADTEST 공연 9개에 예매 20,594건 잔재).
echo "SELECT seat_id, COUNT(*) c FROM booking
       WHERE performance_id=$PERF_ID GROUP BY seat_id HAVING c > 1;" | SQL   # 0행

# (a1-2) 1회 여정 가드 확인. 같은 사람이 두 번 예매했으면 시나리오가 깨진 것이다.
echo "SELECT user_id, COUNT(*) c FROM booking
       WHERE performance_id=$PERF_ID GROUP BY user_id HAVING c > 1;" | SQL   # 0행

# (a1-3) 총괄 — 셋이 같아야 한다. 위 두 쿼리가 0행인 것과 같은 말이지만 수치로 남는다.
echo "SELECT COUNT(*) bookings, COUNT(DISTINCT seat_id) distinct_seats,
             COUNT(DISTINCT user_id) distinct_users
        FROM booking WHERE performance_id=$PERF_ID;" | SQL

# (a2) 한 좌석에 전 VU 가 몰리는 회차 (seat-contention, #344 형).
#      (a1) 을 쓰지 않는다 — '예매 N건이 같은 seat_id' 가 이 회차에서는 정상이다.
#      오버셀 = '두 예매가 동시에 그 좌석을 보유했는가' 이고, 예매 생성은 좌석 점유가 아니다.
#      seat.version 사이클 수와 hold_total{result} 카운터로 판정한다 —
#      원문은 260813-598-consumer-concurrency/metadata.txt 의 OVERSELL_SQL_A~E.

# (b) 유령 HOLD — 좌석은 HOLD 인데 대응 booking 이 없거나 이미 PENDING 을 벗어났다. 0행이어야 한다.
#     "HOLD 좌석 수 <= 예매 수" 로 재면 안 된다. §13.1-(e) 대로 예매가 5-6분이면 만료돼
#     좌석이 풀리므로 종료 시점 HOLD 는 예매 수보다 훨씬 작고, 그 부등식은 항상 참이라 검출력이 0 이다.
#     시드 코호트(LT-*)는 reset_e2e.sql 이 일부러 보존하므로 booking_number 로 제외한다.
echo "SELECT s.seat_id, s.booking_number, b.booking_status
        FROM seat s
        JOIN performance p ON p.performance_id=s.performance_id AND p.title LIKE 'LOADTEST-%'
        LEFT JOIN booking b ON b.booking_number = s.booking_number
       WHERE s.seat_status='HOLD'
         AND s.booking_number NOT LIKE 'LT-%'
         AND (b.booking_id IS NULL OR b.booking_status <> 'PENDING');" | SQL

# (c) 앱이 만든 예매 총수 — k6 요약의 e2e_booking_created 성공 건수와 일치해야 한다.
echo "SELECT COUNT(*) AS app_bookings FROM booking b
        JOIN performance p ON p.performance_id=b.performance_id AND p.title LIKE 'LOADTEST-%'
       WHERE b.booking_number NOT LIKE 'LT-%';" | SQL
```

티켓 이중 발급·이벤트 유실은 `load-test/chaos/verify-inbox.sql`·`verify-loss.sql`을 그대로 쓴다.

### 13.5 포화점 판정과 무효 판정

**포화점**은 세 신호가 동시에 서는 지점이다. 하나만으로는 판정하지 않는다.

1. 도착률(`E2E_STAGE_RATES`)을 올려도 **실제 RPS가 더 이상 증가하지 않는다** — 5분 유지 구간의 값으로만 본다
2. `dropped_iterations`가 발생한다 (k6가 목표 도착률을 못 채운다)
3. 호스트 CPU가 수렴한다 (100% 부근에서 평평해진다)

> ⚠️ **`dropped_iterations`는 두 가지 원인이 겹친다.** VU 부족이면 생성기 문제이고, VU에 여유가 있는데도 나면 대상 포화다. `E2E_PRE_ALLOCATED_VUS`를 넉넉히 잡아 두고, k6 요약의 `vus_max` 대비 실제 사용량으로 가른다. #402가 스모크에서 `preAllocatedVUs=20`이 부족해 20건을 흘린 전례가 있다.

**무효 판정 — 아래 중 하나라도 걸리면 그 회차는 버린다.**

| 조건 | 뜻 |
|---|---|
| `e2e_seat_exhausted > 0` | 좌석 고갈. 시딩 규모나 `E2E_PURCHASE_PERF_IDS`가 부족하다 |
| `e2e_seat_conflict > 0` | 유일 배정이 깨졌다. 리셋 누락이거나 코호트가 겹쳤다 |
| setup 단계 `fail` | 좌석 간격이 일정하지 않다(다른 코호트가 중간을 점유). `reset_e2e.sql` 후 재시도 |
| `ticketrush_seat_lock_contention_total` 증가 | **판정이 concurrency 값에 달렸다(#596).** ⚠️ 먼저 `spring.kafka.listener.concurrency`를 확인한다.<br>· **1이면** §10.1대로 이 카운터는 구조적으로 0이므로, 0이 아니면 **이전 회차의 좌석 락이 Redis에 남아 있다** = 리셋의 Redis 단계 실패다.<br>· **1보다 크면(기본 3) 0이 아닌 것이 정상이다** — 서로 다른 스레드가 같은 좌석을 집으면 오른다. 이 값만 보고 회차를 중단하지 않는다. 잔류 락을 의심할 땐 부하 시작 **전** `seat:lock:*` 키가 0건인지로 판정한다(§리셋 절차).<br>어느 쪽이든 홀드가 보상 처리되면 409가 아니라 `SeatHoldFailedEvent`로 나가므로 `e2e_seat_conflict`에는 잡히지 않는다 |
| k6 → Prometheus remote-write 터널 끊김 | 클라이언트 시계열 소실. 서버 축만으로 판정할지 리포트에 명시(#496 §6 전례) |
| 측정 중 인스턴스 중지 | 2026-07-23에 공유 IAM 사용자의 수동 `StopInstances`로 측정이 날아간 사례가 있다. 시간대를 공지한다 |

### 13.6 PromQL

부하 종료 후 Grafana Explore(§5)에서 실행한다. **부하 중에는 대시보드를 열지 않는다**(패널 쿼리가 측정 대상 CPU를 먹는다, [ADR 0004](adr/0004-load-test-execution-topology.md)).

`job` 라벨은 MVC 7개가 전부 `ticketrush-services`다. **서비스 구분은 `instance`**(`booking-service:8090`, `seat-service:8090` …).

```promql
# ── 유입 축 (k6 클라이언트, 퍼센타일 SSOT) ──────────────────────────────
# ⚠ k6 는 quantile 라벨을 만들지 않는다. docker-compose.yml 의
#   K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg" 대로 _p95/_p99/_avg 접미사 게이지가 된다.
#   값은 '초' 단위라 ms 로 보려면 1000 을 곱한다(§12 캡처 링크와 같은 관용구).
sum(rate(k6_http_reqs_total[1m]))
sum by (name) (rate(k6_http_reqs_total[1m]))          # 엔드포인트별 (tags.name)
1000 * k6_http_req_duration_p95
1000 * k6_http_req_duration_p99
k6_dropped_iterations_total                            # 포화 신호

# 여정 단계별 지연 — 어느 단계가 먼저 무너지는지 가른다(시나리오가 만드는 커스텀 Trend)
1000 * k6_e2e_perf_list_duration_p95
1000 * k6_e2e_perf_detail_duration_p95
1000 * k6_e2e_seat_layouts_duration_p95               # 응답이 가장 큰 경로 (#469 근거)
1000 * k6_e2e_booking_duration_p95
1000 * k6_e2e_browse_journey_duration_p95             # 조회 여정 3요청 합

# 지연을 단계별로 쪼갠다 — 이 회차에서 가장 중요한 대조다.
# waiting(TTFB, 서버가 첫 바이트를 줄 때까지) 대비 receiving(본문 수신)이 크면
# 병목은 처리가 아니라 전송이다(= 응답 크기·압축 부재). §13.1-(f) 참조.
1000 * k6_http_req_waiting_p95
1000 * k6_http_req_receiving_p95
rate(k6_data_received_total[1m])                       # 실효 수신 대역

# 좌석 배정 건전성 — Rate 메트릭은 _rate 접미사를 받는다(2026-07-27 실측으로 확인).
k6_e2e_seat_conflict_rate                              # 0 이어야 한다
k6_e2e_seat_exhausted_rate                             # 0 이어야 한다
k6_e2e_booking_created_rate

# ── 처리 축 (서버 관점) ────────────────────────────────────────────────
sum by (instance) (rate(http_server_requests_seconds_count{job="ticketrush-services"}[1m]))
histogram_quantile(0.95, sum by (le, instance) (
  rate(http_server_requests_seconds_bucket{job="ticketrush-services"}[5m])))
histogram_quantile(0.99, sum by (le, instance) (
  rate(http_server_requests_seconds_bucket{job="ticketrush-services"}[5m])))

# ── 병목 후보 ──────────────────────────────────────────────────────────
hikaricp_connections_pending{job="ticketrush-services"}          # 풀 10/서비스
hikaricp_connections_active{job="ticketrush-services"}
tomcat_threads_busy_threads{job="ticketrush-services"}           # 상한 200 (#500 으로 노출됨)
max by (instance, topic) (kafka_consumer_fetch_manager_records_lag{job="ticketrush-services"})
ticketrush_outbox_backlog{job="ticketrush-services"}              # 릴레이가 못 따라가면 여기 쌓인다
                                                                  # ⚠ 생성분과 만료분이 섞인다(§13.1-(e))
ticketrush_outbox_in_flight{job="ticketrush-services"}
increase(ticketrush_seat_lock_contention_total[5m])                # 0 이어야 한다 — >0 이면 락 잔존(§13.5)
sum by (instance) (rate(jvm_gc_pause_seconds_sum{job="ticketrush-services"}[5m]))

# ── 호스트 자원 (node-exporter, #465) ──────────────────────────────────
100 * (1 - avg(rate(node_cpu_seconds_total{job="node", mode="idle"}[1m])))
100 * avg(rate(node_cpu_seconds_total{job="node", mode="iowait"}[1m]))
node_memory_MemTotal_bytes{job="node"} - node_memory_MemAvailable_bytes{job="node"}
sum(rate(node_disk_read_bytes_total{job="node"}[1m]))
# 회선 축. device 를 ens5(호스트 NIC)로 고정한다 — #508 이전에는 여기가 컨테이너 veth 라
# 실제의 1/6500 인 값이 나왔고, 대시보드는 "회선 한가함"으로 읽혔다.
rate(node_network_transmit_bytes_total{job="node", device="ens5"}[1m])
rate(node_network_receive_bytes_total{job="node", device="ens5"}[1m])

# ── 컨테이너별 메모리 (#515) ──────────────────────────────────────────
# 위 node_* 는 호스트 총량이라 "어느 컨테이너가 자기 mem_limit 에 가까운지"를 답하지 못한다.
# #509 에서 seat-service 가 cgroup OOM 으로 죽을 때까지 신호가 없었던 것이 그래서다.
# cAdvisor 는 이 호스트(Docker 29 + cgroup v2 + systemd)에서 컨테이너를 열거하지 못해 되돌렸고,
# 대신 container-mem-sampler 가 cgroup v2 를 직접 읽어 node-exporter textfile 로 넘긴다.
# 라벨은 compose 의 container_name 이다(seat-service, ticketrush-mysql …).
ticketrush_container_memory_usage_bytes{container="seat-service"}
ticketrush_container_memory_limit_bytes{container="seat-service"}      # mem_limit 이 지표로 나온다
# 상한 대비 사용률(%) — OOM 여유가 원래 10%대라 이 선이 이 스택의 실질 상한이다
100 * ticketrush_container_memory_usage_bytes / ticketrush_container_memory_limit_bytes
topk(5, ticketrush_container_memory_usage_bytes / ticketrush_container_memory_limit_bytes)
# 회차 peak. memory.peak 은 컨테이너 기동 이후 최댓값이라 회차 사이 재시작하면 그 회차 값이 된다.
ticketrush_container_memory_peak_bytes{container="seat-service"}
# OOM 을 사후 dmesg 없이 지표로 인지한다. 0 이 아니면 그 컨테이너가 cgroup OOM 으로 죽은 것이다.
increase(ticketrush_container_oom_kills_total[<회차 길이>s])
# ⚠ 이 값이 0 이라고 안전한 게 아니라 수집이 멎었을 수 있다 — 아래를 함께 본다.
ticketrush_container_sampler_containers                                # 15 내외. 0 이면 열거 실패
#
# ⚠ 이 값들과 `docker stats` 를 직접 비교하지 말 것. #495 가 Prometheus self-scrape RSS 와
#   docker stats 가 유휴에서 13 MiB 어긋나는 것을 확인했다(ADR 0007 §메모리). 축이 다르다.
# ⚠ docker inspect 의 OOMKilled 는 재시작 뒤라 false 로 나온다 — 믿지 말 것.
#   교차 확인이 필요하면 여전히 `sudo dmesg -T | grep CONSTRAINT_MEMCG` 다.

# ── 게이트웨이 축 (연결 거부는 서버 축에 안 잡힌다 — #496 §2.4) ─────────
sum(rate(http_server_requests_seconds_count{job="gateway", status=~"5.."}[1m]))
sum(rate(http_server_requests_seconds_count{job="gateway"}[1m]))

# ── 검표 스모크 (#500 이관 항목) ───────────────────────────────────────
increase(resilience4j_circuitbreaker_not_permitted_calls_total[10m])   # 0 이어야 한다
resilience4j_circuitbreaker_slow_call_rate
resilience4j_circuitbreaker_failure_rate
```

> ⚠️ **서버 축 퍼센타일은 2s에서 포화돼 보인다.** #495가 넣은 `slo` 버킷의 최대 경계가 2s이고 그 위는 `+Inf`에만 세어진다([ADR 0007](adr/0007-observability-stack-colocation.md) §히스토그램). 포화 구간에서 서버 p99가 2s에 붙으면 "2s 이상"으로만 읽고, 실제 값은 k6 축에서 본다.
>
> ⚠️ **게이트웨이의 `uri` 라벨은 `/**`·`UNKNOWN`으로 뭉개진다.** 엔드포인트별 퍼센타일은 MVC job에서만 얻는다.

### 13.7 한계 — 리포트에 그대로 옮길 것

- **단일 EC2에 앱 8개 + Kafka·MySQL·Redis·관측 스택이 동거한다.** 절대 수치에 포화가 섞인다(§10.5·§12와 동일한 단서). 이 회차의 산출물은 "앱의 성능"이 아니라 **"이 구성의 최대치"** 다.
- **Nginx가 부하 경로에 있는데 전용 지표가 없다.** 게이트웨이 앞단이 상한인지 가릴 수 없다(§13.1-(d)).
- **결제·티켓 발급이 여정에서 빠져 있다.** 파이프라인 backlog 회복시간은 이 회차로 답하지 못한다(§13.1-(b)).
- **결제를 안 하므로 예매가 전부 5-6분 뒤 만료되고, 그 만료가 outbox 부하를 사실상 2배로 만든다**(§13.1-(e)). `ticketrush_outbox_backlog`와 릴레이 처리량 수치에는 생성 트래픽과 만료 트래픽이 섞여 있다. 결제까지 도는 실제 운영과 이벤트 구성이 다르다는 뜻이므로, 이 회차의 outbox 수치를 운영 예측에 그대로 쓰면 안 된다.
- **k6 생성기가 가정용 회선을 탄다.** 좌석맵 응답이 공연당 200KB대라 조회 도착률이 높으면 다운로드 대역이 먼저 찰 수 있다. k6 축과 서버 축의 차이가 곧 네트워크 왕복이므로 그 간격이 벌어지는지 함께 본다([ADR 0004](adr/0004-load-test-execution-topology.md) §한계).
- **좌석을 유일 배정하므로 경합은 재지 않는다.** 경합 하의 거동은 §10(#344)이 SSOT다.

---

## 14. 좌석 상태 집계·SSE 대량 구독 측정 (#403)

좌석 조회 경로에서 마지막으로 남은 두 공백을 잰다 — **상태별 집계**(`GET /api/v1/seat/{performanceId}/seat-counts`)와 **실시간 구독**(`GET /api/v1/seat/{performanceId}/seat-status/stream`). 기존 실측은 전부 `seat-layouts`였다(§13, ADR 0006).

**이 회차는 배포가 아니라 실행이다.** 앱 코드 변경 0줄로 현 배포본에서 측정한다(#512의 "`test` 라벨은 배포 묶음에 넣지 않는다" 규약).

### 14.1 이슈 서사와 실측의 차이 — 먼저 읽을 것

**(a) "전송 스레드풀 포화"의 기전이 이슈 서술과 다르다.**

이슈 본문은 `queue(1000) 적체 → max(16)까지 스레드 증가`로 포화를 서술한다. 그런데 큐에 쌓이는 것은 *구독자*가 아니라 **이벤트**다. `SeatStatusSseEventSender.send()`는 이벤트 1건당 executor 태스크 1개를 던지고, **그 태스크 하나가 구독자 전원에게 순차로 `emitter.send()`** 한다(`SeatStatusSseEventSender.java:40-54`).

즉 구독자 N명은 스레드 수요를 N배로 만드는 것이 아니라 **태스크 1개의 길이를 N배로** 만든다. 포화 조건은 `이벤트 발생률 × (N × send 시간) > core 4` 다. 그래서 이 회차는 **이벤트율을 고정하고 구독자 수만 계단으로 올린다**.

> ⚠️ 이슈가 경고한 대로 `ThreadPoolTaskExecutor`는 **큐가 가득 차야** max(16)까지 늘어난다. 큐 1000이 차기 전까지 실질 동시성은 **4**다. 포화 곡선에서 "스레드가 왜 안 느는가"를 오독하지 않으려면 이 순서를 전제로 읽는다.

**(b) 큐가 차면 지연이 아니라 유실이다.**

거부 핸들러가 기본값(`AbortPolicy`)이라 `RejectedExecutionException`이 나고, sender는 `log.warn`만 남기고 **이벤트를 조용히 드롭한다**(`:35-37`). 따라서 포화의 증상은 "느려짐"이 아니라 **수신 누락**이다. §14.6의 두 축(거부 로그 / 수신 누락률)이 같은 시각에 함께 튀어야 확정된다.

**(c) 전파 지연은 서버·클라이언트 시계 차로 잴 수 없다.**

`SeatStatusChangedResponse`에 발생 시각 필드가 없고(`performance_id, seat_id, seat_layout_id, seat_number, seat_status, hold_expired_at`), EC2와 로컬 k6는 다른 호스트다. 서버가 찍은 시각과 클라이언트 수신 시각을 빼면 두 호스트의 시계 차가 그대로 섞인다.

그래서 **probe VU**가 자기가 구독하고 자기가 예매를 걸어, **자기 `seat_id` 이벤트가 돌아오는 시각차**를 잰다. 시작과 끝이 같은 k6 프로세스의 같은 시계라 시계 차를 타지 않는다. probe는 매 iteration마다 새로 구독하므로 `CopyOnWriteArrayList`의 **뒤쪽**에 등록된다 — 팬아웃 전 구간을 통과한 뒤 받는 **최악값**이다.

측정값에는 booking API + Kafka + HOLD 트랜잭션 + SSE 팬아웃이 전부 들어 있다. 팬아웃 몫만 떼려면 같은 iteration의 `sse_probe_booking_duration`을 뺀다(그래도 Kafka 구간은 남는다 — 클라이언트에서 더 잘게 가를 방법이 없다).

**(d) HOLD 비율의 영향은 "만료 여부"가 아니라 "HOLD 행의 존재"다.**

집계는 만료 HOLD(`holdExpiredAt <= now`)를 AVAILABLE로 선반영한다(`SeatRepository.java:21-22`). 좌석이 전부 AVAILABLE이면 `hold_expired_at`이 NULL이라 datetime 비교가 사실상 생략되어 실제보다 낙관적인 값이 나온다. 그래서 시딩에 SOLD/HOLD를 섞는다.

단, **만료된 HOLD를 넣으면 안 된다** — 60초 주기 `SeatStatusScheduler`가 측정 도중 해제해 **상태 분포가 회차 중간에 변한다**(tick당 최대 2,000건). 미만료 HOLD(만료시각 +6시간)로 넣어 분포를 고정한다. 만료 HOLD가 필요한 것은 §14.6의 큐 포화 회차뿐이고, 그건 `@mode='expire'`로 따로 만든다.

**(e) `now`는 DB 시계가 아니라 앱 JVM 시계다.**

`SeatGetStatusCountsUseCase.java:18`이 `LocalDateTime.now()`를 만들어 파라미터로 넘긴다(쿼리 안에 `CURRENT_TIMESTAMP`가 없다). 배포본 앱은 UTC, MySQL 컨테이너는 `system_time_zone=KST`라 세션 `NOW()`가 9시간 앞선다(§11의 함정). 세션 `NOW()`로 `hold_expired_at`을 박으면 상태 분포가 통째로 어긋나므로 시드가 `UTC_TIMESTAMP()`를 쓴다.

**(f) 선결 문제 — SSE가 외부에서 도달 불가였다(nginx 버퍼링).**

게이트웨이 라우트는 이미 있었다 — `gateway-service/.../application.yml:50-56`의 `id: seat-sse-service`가 일반 seat 라우트보다 **앞에** 선언돼 있고 `response-timeout: -1`이다. 막힌 곳은 그 앞단인 **nginx**였다.

기본값 `proxy_buffering on`이 업스트림 응답을 버퍼에 모았다가 내보내는데, SSE는 커넥션을 열어둔 채 이벤트를 조금씩 흘리므로 버퍼가 차거나 커넥션이 끊길 때까지 아무것도 도달하지 않는다. 인터넷에 열린 포트는 nginx 443 하나뿐이므로 이 기능은 **외부에서 쓸 수 없는 상태**였다. #402의 검표 라우트 누락과 같은 계열이고, 이번에도 측정을 붙이는 과정에서 드러났다.

**실증:** 게이트웨이 8080 직결 → `event:connected` 즉시 / 같은 시각 https → 8초 동안 헤더조차 없음. 수정 후 https → 헤더 즉시 + `event:connected` 수신.

수정은 정규식 location 하나다(`deploy/nginx/api.ticketrush.store.conf`). `proxy_read_timeout`을 3600s로 올린 것은 기본값 60s가 SseEmitter 타임아웃(30분)보다 짧아 서버가 정상으로 여기는 커넥션을 nginx가 1분마다 끊기 때문이다.

> **nginx 설정은 이번에 저장소로 편입했다.** 그전까지 EC2 호스트에만 있어서 저장소를 아무리 읽어도 이 결함을 알 수 없었다. **호스트를 손으로 고치지 말고 `deploy/nginx/`를 고쳐 배포한다** — 두 곳이 갈라지면 다음 사람이 같은 함정을 다시 밟는다.
> ```bash
> tr -d '\r' < deploy/nginx/api.ticketrush.store.conf | ssh <EC2> \
>   'sudo tee /etc/nginx/sites-available/api.ticketrush.store > /dev/null \
>    && sudo nginx -t && sudo systemctl reload nginx'
> ```

**(g) 두 엔드포인트 모두 인증이 필요 없다.** seat-service `SecurityConfig`가 `/api/v1/seat/**`를 `anyRequest().permitAll()`로 둔다. 토큰은 이벤트를 만드는 예매 축(`POST /api/v1/booking`)에만 필요하다.

### 14.2 사전 게이트 — 하나라도 어긋나면 회차를 시작하지 않는다

```bash
ssh -i ~/ticket_rush_ssh.pem ubuntu@<EC2>
```

| # | 확인 | 명령 | 기대 |
|---|---|---|---|
| G0 | SSE가 인터넷에서 도달 | `timeout 6 curl -sN -H 'Accept: text/event-stream' https://api.ticketrush.store/api/v1/seat/<SSE공연>/seat-status/stream` | `event:connected` 즉시 수신. 무응답이면 §14.1-(f) — nginx 설정이 되돌아간 것이다 |
| G1 | 집계 커버링 인덱스 실존 | `docker exec -i ticketrush-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" ticket_rush -e "SHOW INDEX FROM seat"'` | `idx_seat_performance_id_status_hold_expired_at` 존재(#521). **인덱스 적용 전후 회차를 비교하는 경우에는 그 회차의 실제 상태를 metadata 에 기록하고 진행한다** |
| G2 | executor 메트릭 노출 | `docker exec seat-service wget -qO- localhost:8090/actuator/prometheus \| grep executor_` | `executor_queued_tasks{name="seatStatusSseExecutor"}` 존재 |
| G3 | 배포본 = 저장소 | `docker inspect --format '{{.Config.Image}}' $(docker ps -q)` | 전 서비스 `IMAGE_TAG` 동일 |
| G4 | seat-service 기준선 | `docker stats --no-stream seat-service` | 400 MiB 내외. 550 MiB면 **재시작 후 시작**(§14.8) |

> **G1이 실패하면 측정 자체가 무의미하다.** 인덱스가 없으면 집계가 풀스캔이 되어 "앱의 특성"이 아니라 "인덱스 부재"를 재게 된다. `@Index`는 `ddl-auto=update`인 로컬/신규 초기화 DB에서만 생성되고 prod(`validate`)는 부재를 검출하지 못한다(`Seat.java:50-73`). 없으면 먼저 수동 DDL을 넣는다 — 그 런북이 같은 javadoc에 있다.

> **G2는 #403에서 실패했고 그 원인은 PR #519가 고쳤다.** `SeatStatusSseConfig`의 `@Bean` 반환 타입이 `Executor`라 Boot의 `TaskExecutorMetricsAutoConfiguration`이 `TaskExecutor` 타입으로 후보를 모으는 단계에서 이 빈을 놓쳤다. 반환 타입을 `ThreadPoolTaskExecutor`로 좁혀 해결했으므로 **그 변경이 배포된 이미지에서는 G2가 통과해야 한다.** 그래도 실패하면 배포본이 그 커밋을 담고 있는지(G3)부터 본다. 큐 깊이 시계열 없이 회차를 돌리면 §14.6의 판정이 로그 파싱으로 되돌아간다.

> **G5 — 발행 경로 태그(#520).** `docker exec seat-service wget -qO- localhost:8090/actuator/prometheus | grep ticketrush_seat_sse_event_published` 가 **source 라벨 5종을 전부** 내야 한다(`booking_hold`·`expire_single`·`scheduler_fallback`·`confirm_sold`·`refund_release`). 카운터를 기동 시 미리 등록하므로 발행이 0건인 경로도 나온다 — **라벨이 빠져 있으면 배포본에 #520이 없는 것이지 그 경로가 조용한 것이 아니다.**

### 14.3 시딩

`seed_seat_counts.sql`은 **기존 `LOADTEST` 코호트(§13의 20,800석)를 건드리지 않는다.** 타이틀 접두사가 `LTC-`라 `cleanup_load.sql`의 `LOADTEST-%` 패턴에 걸리지 않는다.

**로컬 저장소에서** stdin 으로 흘려보낸다. 파라미터를 `--init-command` 로 넘기지 않는 것은 `seed_entry.sql` 이 기록한 함정 때문이다 — 셸을 한 겹 더 지나면서 값이 조용히 잘린다. SET 문을 파일 앞에 붙이면 값이 데이터로만 지나간다. `tr -d '\r'` 은 Windows 체크아웃의 CRLF 제거용이다.

```bash
run_seed() {
  { printf "SET %s;\n" "$1"; cat load-test/seed/seed_seat_counts.sql; } | tr -d '\r' \
  | ssh -i ~/ticket_rush_ssh.pem ubuntu@<EC2> \
      'docker exec -i ticketrush-mysql sh -c '"'"'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" ticket_rush'"'"''
}

# 스케일 A — 600석
run_seed "@i_confirm_loadtest_db=1, @perf_tag='A', @seats=600, @sold_pct=20, @hold_pct=20"
# 스케일 B — 3,000석 (상태 비율은 A 와 같아야 두 곡선을 겹칠 수 있다)
run_seed "@i_confirm_loadtest_db=1, @perf_tag='B', @seats=3000, @sold_pct=20, @hold_pct=20"
# SSE 대상 — 예매 부하로 HOLD 전이를 유발하므로 전부 AVAILABLE
run_seed "@i_confirm_loadtest_db=1, @perf_tag='SSE', @seats=12000, @sold_pct=0, @hold_pct=0"
```

- **재실행마다 `performance_id`가 바뀐다.** 이 시드는 NOT EXISTS idempotency 대신 삭제+재삽입을 한다 — 규모를 바꿔 재실행하는 것이 정상 사용법인데 NOT EXISTS로는 이전 규모의 잔여 좌석이 남아 분포가 파라미터와 달라지기 때문이다. 검증 쿼리 출력을 매번 다시 읽는다.

> **코호트를 하루 넘겨 재사용할 때는 `@mode='refresh'`를 먼저 돌린다.** 미만료 HOLD의 만료시각이 `+6시간`이라, EC2를 껐다가 다음 날 켜면 그 사이에 만료돼 있고 부팅 직후 스케줄러가 전부 AVAILABLE로 해제한다. 실측에서 A는 `hold 120 → 0`, B는 `600 → 0`이 됐다(그 해제가 `executor_completed_tasks_total`을 정확히 720 = 120+600으로 올린 것이 부수 증거다). 그대로 재면 **회차 1과 회차 2의 상태 분포가 달라 두 곡선을 겹칠 수 없다** — §14.8의 무효 판정 항목이다.
>
> `refresh`는 좌석번호 `S-<i>`의 `i`로 시딩 당시의 배분 규칙을 재현해 **원래 HOLD였던 바로 그 좌석만** 되돌리고, 공연을 지우지 않으므로 `performance_id`가 보존된다. 재시딩하면 id가 바뀌어 이전 회차와의 연결이 끊긴다.
> ```bash
> run_seed "@i_confirm_loadtest_db=1, @perf_tag='A', @mode='refresh', @sold_pct=20, @hold_pct=20"
> run_seed "@i_confirm_loadtest_db=1, @perf_tag='B', @mode='refresh', @sold_pct=20, @hold_pct=20"
> ```
> 되돌린 뒤 `/seat-counts` 응답이 `expect_*`와 일치하는지 한 번 더 대조한다.
- 검증 쿼리의 **두 번째 SELECT(`expect_*`)가 `/seat-counts` 응답과 일치해야 한다.** 시딩 직후 `curl`로 한 번 대조한다. 이 출력이 리포트 "시딩 규모" 절의 원자료다(완료조건 1).
- SSE 코호트의 `@seats`는 `SSE_MUTATE_RATE × 회차 길이(초)` 이상이어야 한다. 예매는 좌석 1개를 비가역 소모하고, 부족하면 회차 후반이 통째로 이벤트 0이 된다. 시나리오 `setup()`이 시작 전에 이걸 검증하고 부족하면 죽는다.
- **SSE 회차를 한 번이라도 돌렸으면(스모크 포함) 다음 회차 전에 SSE 코호트를 재시딩한다.** `mutate`는 코호트 앞에서, `probe`는 뒤에서 좌석을 소모하므로 AVAILABLE `seat_id`가 양끝부터 뚫린다. `setup()`의 간격 균일성 검증이 이걸 잡아 회차를 시작조차 하지 않는다(그 검증이 없으면 409가 "정상 경합"으로 위장돼 이벤트 수가 조용히 줄어든다). 스케일 A·B와 달리 SSE 코호트는 `refresh`로 되돌릴 수 없다 — 소모가 상태 분포가 아니라 좌석 점유 자체이기 때문이다.

### 14.4 회차 1·2 — seat-counts 좌석 수 대비 곡선

```bash
# 로컬에서. -e 위치 규칙과 --no-deps 는 §7.2 와 동일하다.
docker compose --profile loadtest run --rm --no-deps \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://host.docker.internal:9090/api/v1/write \
  k6 run /scripts/scenarios/seat-counts.js \
  -e BASE_URL=https://api.ticketrush.store \
  -e PERF_ID=<스케일A 의 performance_id> \
  -e COUNTS_EXPECTED_SEATS=600 \
  -e COUNTS_STAGE_RATES=40,80,160,240,320,480 \
  -e COUNTS_STAGE_DURATION=5m \
  -e COUNTS_PRE_ALLOCATED_VUS=100 -e COUNTS_MAX_VUS=800
```

회차 2는 `PERF_ID`와 `COUNTS_EXPECTED_SEATS`만 바꾼다. **계단·유지시간은 같아야 두 곡선을 겹칠 수 있다.**

- 단계당 **5분**은 완료조건이자 관측 하한이다(Prometheus 스크랩 15초 → 단계당 20표본).
- **포화 판정**: 도착률을 올려도 실제 RPS가 늘지 않음 + `dropped_iterations` 발생 + 호스트 CPU 수렴.
- `seat_counts_scale_mismatch`가 0이 아니면 **그 회차는 폐기한다** — 엉뚱한 공연을 쟀거나 시딩이 덜 된 것이다.
- `preAllocatedVUs`/`maxVUs`를 넉넉히 준다. 그래야 `dropped_iterations`를 **VU 부족이 아니라 포화 신호로** 읽을 수 있다(§13.5와 같은 선).

**계단 근거 — 캘리브레이션 실측(2026-07-28).** 스케일 B(3,000석)에 320 → 640/s를 45초씩 넣어 무릎 위치를 먼저 찾았다.

| 축 | 값 | 읽는 법 |
|---|---|---|
| 목표 도착률 | 640/s | |
| 실제 처리량 | **246.8 rps 고원** | 도착률을 2.6배 올려도 여기서 멈춘다 |
| 호스트 CPU | **99.96%** | 이것이 상한이다(#509와 같은 결론) |
| tomcat busy | **50 / 50** | 상한 도달 |
| **HikariCP pending** | **41** (풀 10) | 집계 쿼리가 커넥션을 물고 줄을 세운다 |
| p95 | 3.52s / 실패 5.30% / dropped 21,863 | 무너진 구간 |

무릎이 **약 247 rps**이므로 그 아래위를 감싸는 `40,80,160,240,320,480`을 쓴다. 240은 무릎 바로 위, 480은 확실히 무너지는 지점이다. 회차 길이는 30분 40초다.

> `seat-counts` 응답은 203 B로 `seat-layouts`(2,080석 기준 약 230 KB)보다 3자리 작다. 회선·직렬화가 빠지고 집계 스캔만 남으므로 §13의 좌석맵 계단(10~80)보다 한 자리 위를 본다.

### 14.5 비교 회차 — seat-layouts 대비 비용

```bash
docker compose --profile loadtest run --rm --no-deps ... k6 run /scripts/scenarios/seat-counts.js \
  -e PERF_ID=<스케일A> -e COUNTS_EXPECTED_SEATS=600 -e COUNTS_COMPARE=1 \
  -e COUNTS_STAGE_RATES=20 -e COUNTS_STAGE_DURATION=3m
```

같은 공연·같은 부하·같은 iteration에서 두 엔드포인트를 잰다. DB 접근 행수는 같고(둘 다 `performance_id`로 전 행 스캔) 차이는 응답 크기와 직렬화뿐이라, 차분이 곧 그 비용이다.

> ⚠️ **스케일 B(3,000석)에서는 하지 않는다.** 3,000석 `seat-layouts` 응답이 CPU·메모리를 삼켜 비교 자체를 오염시킨다 — #509가 2,080석 좌석맵으로 seat-service를 cgroup OOM까지 몰고 간 경로가 정확히 이것이다.

### 14.6 회차 3·4 — SSE 팬아웃과 큐 포화

**이 시나리오는 `k6-sse` 이미지에서만 돈다.** k6 기본 바이너리에는 SSE 클라이언트가 없다(근거는 `load-test/Dockerfile.k6-sse` 주석).

```bash
docker compose --profile loadtest build k6-sse      # 최초 1회

# 회차 3 — 팬아웃 곡선
docker compose --profile loadtest run --rm --no-deps \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://host.docker.internal:9090/api/v1/write \
  k6-sse run /scripts/scenarios/seat-sse-fanout.js \
  -e BASE_URL=https://api.ticketrush.store \
  -e SSE_PERF_ID=<SSE 코호트의 performance_id> \
  -e LOAD_USER_PASSWORD='<평문>' \
  -e SSE_SUBSCRIBER_STEPS=100,300,600 \
  -e SSE_STEP_DURATION=10m \
  -e SSE_MUTATE_RATE=5
```

- 구독자 단계당 **10분**은 완료조건이다 — 장기 커넥션의 누수·타임아웃 전 구간을 본다. 기본값 기준 총 31분 30초이고, 서버 emitter 타임아웃이 30분이라 **가장 먼저 붙은 커넥션은 회차 끝에서 타임아웃에 닿는다.** `sse_connection_closed`가 그 시각에 오르는 것은 정상이다(그 전에 오르면 다른 원인이다).
- **VU 1개 = 커넥션 1개**다(`sse.open`이 커넥션이 닫힐 때까지 블로킹한다). VU 수가 곧 동시 구독자 수다.
- ⚠️ **이벤트율은 `SSE_MUTATE_RATE` 하나로 고정되지 않는다.** 좌석 락 TTL이 5분이라(`SeatLockUseCase.LOCK_TTL_MINUTES`) 예매 5분 뒤 그 좌석이 해제되며 **두 번째 이벤트**를 낸다. 즉 회차 5분 이후의 정상 상태 이벤트율은 `SSE_MUTATE_RATE × 2`다.
  해제 경로는 **둘**이고 성격이 다르다 — `SeatReleaseSingleUseCase`(**Redis 키 만료 이벤트**로 1건씩 즉시 해제, 주 경로, 고르게 퍼진다)와 `SeatStatusScheduler`(`@Scheduled(fixedDelay = 60000)` fallback, 놓친 것을 60초마다 쓸어 담는다). **정상 상태의 해제는 60초 버스트가 아니다.** 스케줄러가 큰 덩어리를 만드는 것은 만료 HOLD가 대량으로 쌓여 있을 때이고, 그 조건을 인위적으로 만드는 것이 아래 회차 4다.
  이건 측정 오염이 아니라 시스템의 실제 성질이므로 통제하지 말고 **리포트에 명시**한다. 전파 지연 표본이 한쪽에 몰리면 값이 튀므로 `probe`를 낮은 도착률로 회차 전 구간에 고르게 뿌려 계단별 p95를 잡는다(그래서 `SSE_PROBE_PER_MINUTE`가 2다).
- **수신 누락률** = `1 − (sse_events_received / (구독자 수 × 발생 이벤트 수))`. 발생 이벤트 수는 `sse_mutate_created` 성공 건수이며, **권위는 k6가 아니라 SQL**이다(§10.2 oversell 검증과 같은 선):
  ```sql
  SELECT COUNT(*) FROM seat WHERE performance_id = <SSE 공연> AND seat_status = 'HOLD';
  ```
- 병행 관측: seat-service RSS(여유가 10%뿐이다 — **OOM은 실패가 아니라 결과로 기록한다**), gateway 메모리(512 MiB), `tomcat_threads_current`.

**회차 4 — 큐 포화·거부.** 큐 1000을 채우는 가장 확실한 경로는 예매 부하가 아니라 **스케줄러 fallback 버스트**다. tick당 최대 2,000 이벤트(`chunk-size 25 × max-chunks 80`)가 한 번에 executor로 투입되고, 이는 큐 용량의 2배다.

구독자 600명이 붙어 있는 상태에서 EC2에 아래를 넣고 다음 60초 tick을 기다린다. `@mode='expire'`는 **공연을 지우지 않으므로 `performance_id`가 보존된다** — 붙어 있는 구독자가 그대로 유효하다.

```bash
run_seed "@i_confirm_loadtest_db=1, @perf_tag='SSE', @mode='expire', @expire_count=2000"
```

관측할 것:
- `executor_queued_tasks`가 1000에 붙는 시각
- `executor_pool_size_threads`가 4 → 16으로 늘어나는 시각 (**큐가 다 찬 뒤에만 늘어난다**)
- 거부 로그: `docker logs --since 5m seat-service | grep -c "전송 작업이 거부"` (G2 실패 시 이것이 유일한 증적)
- 같은 시각에 §14.6의 수신 누락률이 튀는가 — **두 축이 일치하면 확정**

### 14.7 PromQL

```promql
# ── 집계 쿼리 (서버 관점) ─────────────────────────────────────────────
# #495 의 slo 버킷이 있어 이 회차는 서버 p95/p99 산출이 가능하다(#402 와 다르다).
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{
  instance="seat-service:8090", uri="/api/v1/seat/{performanceId}/seat-counts"}[1m])) by (le))

# seat-layouts 대비 비용 — uri 만 다른 같은 축이라 나란히 읽으면 그대로 비교가 된다
sum(rate(http_server_requests_seconds_sum{instance="seat-service:8090"}[1m])) by (uri)
  / sum(rate(http_server_requests_seconds_count{instance="seat-service:8090"}[1m])) by (uri)

# ── SSE 전송 스레드풀 (G2 통과 시에만) ────────────────────────────────
executor_queued_tasks{name="seatStatusSseExecutor"}        # 1000 이 상한
executor_active_threads{name="seatStatusSseExecutor"}
executor_pool_size_threads{name="seatStatusSseExecutor"}   # 4 -> 16 은 큐 포화 뒤에만

# 발행 경로별 도착률(#520). 위 큐 깊이와 겹쳐 읽는 것이 이 축의 용도다 — #403 은 큐가 평균 50 ·
# 최대 307 까지 튀는 것을 봤지만 그 불균일이 어느 경로에서 왔는지 가르지 못했다(§10).
# source 값 5종: booking_hold / expire_single / scheduler_fallback / confirm_sold / refund_release
sum by (source) (rate(ticketrush_seat_sse_event_published_total[1m]))
# 스케줄러 fallback 만 떼어 본다. tick 당 최대 2,000 건(chunk 25 x max-chunks 80)이 한 번에 들어가는데
# 큐 용량이 1000 이라, 버스트가 여기서 온다면 이 선이 60초 주기의 톱니로 보인다.
rate(ticketrush_seat_sse_event_published_total{source="scheduler_fallback"}[1m])

# 거부 = 이벤트 유실(#403 이후 추가). 위 큐 깊이가 '왜' 를 말하고 이 카운터가 '얼마나' 를 말한다.
rate(ticketrush_seat_sse_event_rejected_total[1m])
increase(ticketrush_seat_sse_event_rejected_total[<회차 길이>s])   # 회차 유실 총량
# 유실률 = 거부 / (거부 + executor 완료)
increase(ticketrush_seat_sse_event_rejected_total[1h])
  / (increase(ticketrush_seat_sse_event_rejected_total[1h])
     + increase(executor_completed_tasks_total{name="seatStatusSseExecutor"}[1h]))

# ── 커넥션 유지 축 ────────────────────────────────────────────────────
tomcat_threads_current_threads{instance="seat-service:8090"}   # SSE 는 async 라 요청당 스레드를 안 문다
jvm_threads_live_threads{instance="seat-service:8090"}

# ── k6 축 (퍼센타일 SSOT) ─────────────────────────────────────────────
# k6 는 quantile 라벨을 만들지 않는다 — _p95/_p99 접미사 게이지다(§5).
k6_sse_propagation_ms_p95
k6_seat_counts_duration_p95
rate(k6_sse_events_received_total[1m])
rate(k6_sse_connection_closed_total[1m])

# ── 압박 축 ───────────────────────────────────────────────────────────
hikaricp_connections_pending{instance="seat-service:8090"}
rate(node_network_transmit_bytes_total{job="node", device="ens5"}[1m])
```

라벨은 `job`이 아니라 **`instance`** 다(#489에서 확인 — `application` 라벨은 이 스택에 없다).

### 14.8 주의 · 무효 판정

- **회차 사이 seat-service를 재시작한다.** 부하가 끝나도 RSS가 돌아오지 않아(#509 §8: 종료 5분 뒤에도 88%) 다음 회차 기준선이 88%에서 시작한다. `IMAGE_TAG` export를 잊지 않는다.
- **배포 직후 회차는 폐기한다.** JIT 컴파일·클래스 로딩이 섞인다 — #402가 같은 부하에서 CPU 97.9% vs 73.9%, p95 234ms vs 33ms를 봤다. 워밍업 후 재측정한다.
- **호스트 CPU가 먼저 포화한다.** #509가 CPU 99% 도달이 seat-service 스레드 상한보다 2분 30초 앞선다는 것을 확정했다. 이 회차의 포화점은 "seat-counts의 한계"가 아니라 **"이 구성에서 도달한 지점"** 이다.
- **SSE는 압축되지 않는다.** `server.compression`의 기본 mime-types에 `text/event-stream`이 없다(seat-service `application.yml:15-16` 주석). #505의 압축 효과가 이 경로에는 적용되지 않는다.
- **무효 판정** — 하나라도 걸리면 그 회차를 버린다:
  - `seat_counts_scale_mismatch > 0` (엉뚱한 공연 / 시딩 미완)
  - `sse_mutate_exhausted > 0` (좌석 고갈 — 후반 구간에 이벤트가 없다)
  - `sse_mutate_conflict > 0` (유일 배정이 깨졌다 — 다른 코호트와 겹쳤다)
  - `dropped_iterations > 0`인데 원인이 포화가 아니라 `preAllocatedVUs` 부족인 경우
  - 회차 전후로 시딩 상태 분포가 달라진 경우(스케줄러가 HOLD를 해제)
  - 서비스별 `IMAGE_TAG`가 다른 경우
- **단일 인스턴스 기준이다.** emitter가 인스턴스 로컬(`ConcurrentHashMap`)이라 다중 인스턴스로 확장하면 크로스 인스턴스 브로드캐스트가 없다. 이 회차는 그 문제를 다루지 않는다.
- **Git Bash에서 실행할 때는 `MSYS_NO_PATHCONV=1`을 앞에 붙인다.** 안 붙이면 MSYS가 `/scripts/scenarios/...`를 Windows 경로로 바꿔버려 k6가 `C:/Program Files/Git/scripts/...`를 찾다가 죽는다. 컨테이너 안 경로라 로컬에는 없는 파일이므로 에러 메시지가 "스크립트를 못 찾음"으로 나와 원인이 잘 안 보인다.
  ```bash
  MSYS_NO_PATHCONV=1 docker compose --profile loadtest run --rm --no-deps ... k6 run /scripts/scenarios/seat-counts.js ...
  ```
- **로그에서 시각을 뽑을 때는 `^`로 줄 앞을 앵커링한다.** 페이로드에도 시각이 들어 있다. #403에서 거부 로그의 시각 분포를 `grep -oE "T[0-9]{2}:[0-9]{2}:[0-9]{2}"`로 뽑았다가, 줄 맨 앞 타임스탬프와 이벤트의 `holdExpiredAt`을 함께 세어 **존재하지 않는 두 번째 거부 구간을 만들어냈다**(매치 2,289 vs 실제 줄 2,009). 좌석 락 TTL이 5분이라 그 허상이 정확히 5분 뒤에 규칙적으로 나타나 그럴듯해 보였다.
  ```bash
  # 이렇게 — 줄 앞 앵커 + 합계 검산
  docker logs --since 3h seat-service 2>&1 | grep "<패턴>" \
    | grep -oE "^[0-9-]+T[0-9]{2}:[0-9]{2}:[0-9]{2}" | sort | uniq -c \
    | awk '{s+=$1; print} END {print "합계 =", s}'
  ```
  **분포의 합계가 원본 줄 수(`grep -c`)와 같은지 반드시 확인한다.** 이 검산 하나면 위 오류가 그 자리에서 잡힌다.
- **k6 종료코드 99는 실행 실패가 아니라 임계 초과다.** 포화를 일부러 만드는 회차에서는 `http_req_duration`·`http_req_failed` 임계가 당연히 깨진다. 회차가 끝까지 돌았는지는 `running (...)` 마지막 줄의 경과시간과 `iterations` 총합으로 판단하고, 데이터 유효성은 `seat_counts_scale_mismatch`로 판단한다.

### 14.9 증적 그래프 캡처

**Grafana에 이미지 렌더러 플러그인이 없어 PNG를 서버가 만들어 주지 못한다.** Explore를 열어 직접 캡처한다(#346·#347 회차와 같은 관행).

측정 창을 손으로 맞추면 어긋나기 쉬우므로, 쿼리와 UTC 절대 시각을 URL에 박아둔 링크 목록을 회차 증적 디렉토리에 함께 남긴다 — **절차와 링크는 `load-tests/k6/results/260728-403-seat-counts-sse/grafana-capture-links.md`에 있다.** 새 회차를 돌리면 그 파일의 `WINDOWS`(측정 창)와 `GRAPHS`(그래프 정의)만 갈아 끼워 같은 형식으로 다시 만든다.

요지만 옮기면:

1. `ssh -L 3000:localhost:3000 -L 9090:localhost:9090` 터널을 올린다(§7.1 — 3000·9090은 `127.0.0.1` 바인딩이다).
2. http://localhost:3000 에 **먼저 로그인한다.** 로그인 전에 Explore 링크를 열면 로그인 화면으로 튕기면서 쿼리·시간 범위가 유실된다.
3. 링크를 그대로 열고 화면을 캡처해 `graph-*.png`로 증적 디렉토리에 저장한다.

> Prometheus 보존 기간이 지나면 이 링크들은 빈 그래프가 된다. 그래서 같은 디렉토리에 `dump-timeseries.py` 결과(`timeseries-*.json`)를 함께 커밋한다 — 그쪽이 장기 원자료다.

---

## 15. 티켓 발급 파이프라인 backlog 회복시간 측정 (#504)

결제확정 → 티켓발급 **비동기 파이프라인**이 스파이크 유입을 흡수한 뒤 backlog 가 얼마 만에 회복되는지 잰다. #348 완료 조건에서 분리해 나온 항목이다.

**이 회차도 배포가 아니라 실행이다.** 앱 코드 변경 0줄(#512 규약).

### 15.1 이슈 서사와 실측의 차이 — 먼저 읽을 것

**(a) 결제 API 를 탈 수 없어서 이 회차가 생겼다.**

`StubPaymentApprovalClient` 는 `@Profile("!prod")` 이고 측정 대상은 `SPRING_PROFILES_ACTIVE=prod` 단독 배포본이다(ADR 0004). 실 Toss 호출은 `paymentKey` 를 PG 가 발급하므로 k6 가 만들 수 없고, 웹훅도 `paymentKey` 로 PG 에 재조회해 진위를 검증한다. 우회 경로가 없다.

prod 에서 stub 을 허용하는 방향(`@Profile("!prod")` 제거)은 **배포본에 결제 우회 경로가 환경변수 하나로 켜지는 상태**를 만들기 때문에 택하지 않았다. 대신 파이프라인 입구인 `payment-confirmed-topic` 에 이벤트를 직접 주입한다 — #402 가 `seed_entry.sql` 로 코호트를 SQL 로 심은 것과 같은 사상이다.

**(b) 릴레이를 우회하므로 #489 의 발행 상한이 적용되지 않는다.**

`payment-confirmed-topic` 은 payment-service 가 발행하는데, payment-service 는 `app.event-publisher.type: kafka` 라 **outbox 를 거치지 않는다**(booking-service 만 `outbox`). 그리고 이 회차는 발행조차 우회해 브로커에 직접 넣는다.

#348 은 "릴레이가 앞에서 조여 컨슈머까지 부하가 도달하지 않았다"(lag 최대 52)로 끝났다. 그 조임이 사라지므로 **이 회차에서 처음으로 `concurrency=1` 컨슈머가 노출된다.**

**(c) 좌석을 공유하면 안 된다 — #402 와 전제가 다르다.**

`seed_entry.sql`(#402)은 LOADTEST 첫 공연의 MIN seat_id 하나를 25,000 booking 전체가 공유한다. 검표 경로에 seat-service 가 아예 없어서 가능했던 선택이다.

이 회차는 `booking-group` 이 `booking.confirm()` 뒤 seat-service `POST /api/v1/internal/seat/sold` 를 부른다. 좌석을 공유하면 payload 의 `seat_id` 를 하나로 고정해야 하는데, 그러면 `BookingConfirmUseCase` 가 `BOOKING_SEAT_MISMATCH` 로 **확정 단계에서** 전건을 죽인다. 통과시켜도 `SeatConfirmSoldUseCase` 가 `SEAT_CONFIRM_NOT_OWNED`(409)를 내고 booking 이 `SeatConfirmFailedEvent` 를 outbox 에 쌓아 측정 창에 2차 파동이 얹힌다. **좌석 : 예매 = 1:1 로 심는다.**

**(d) 두 스케줄러가 코호트를 시한폭탄으로 만든다.**

- `BookingExpireUseCase` — `PENDING` 이고 `created_at <= now-5분` 인 예매를 EXPIRED 로 넘긴다(`PAYMENT_WAIT_MINUTES=5`, `@Scheduled(fixedDelay=60000)`, 배치 100 × 최대 200회 = **tick 당 20,000건**). 시드가 `created_at` 을 현재 시각으로 박으면 **시딩 5~6분 뒤 코호트가 통째로 EXPIRED** 가 되고, `booking.confirm()` 이 `BOOKING_EXPIRED` 로 죽어 티켓도 좌석 SOLD 도 안 나온다. 회차의 "baseline 5분" 이 정확히 도화선 길이다.
- `SeatStatusScheduler` — 만료된 HOLD 를 60초 주기로 AVAILABLE 로 해제한다. 해제되면 `confirmSoldById(seatId, bookingNumber, HOLD, SOLD)` 가 0행을 갱신해 전건 실패한다.

둘 다 시각 비교라 `seed_payment_pipeline.sql` 이 `created_at`·`hold_expired_at` 을 **6시간 미래로** 밀어 막는다. 검증 SELECT 의 `expiry_safe` 가 그 확인이다.

**(e) 유선 형태가 틀리면 리스너는 실행조차 안 된다.**

Kafka value 는 `PaymentConfirmedEvent` 가 아니라 `DomainEventEnvelope` 이고, 이벤트 본문은 봉투 안 `payload` 에 **JSON 문자열로 escape 되어** 들어간다. 한 메시지 안에서 두 직렬화 규칙이 다르다:

| 층 | 매퍼 | 규칙 |
|---|---|---|
| 봉투 | `JacksonMapperUtils.enhancedJsonMapper()` (spring-kafka 전용) | camelCase |
| payload | 앱 `ObjectMapper` (`JacksonConfig`) | **snake_case** + `yyyy-MM-dd HH:mm:ss` |

그리고 **헤더 `__TypeId__: com.ticketrush.global.event.DomainEventEnvelope` 가 필수**다. `USE_TYPE_INFO_HEADERS=true` 인데 `spring.json.value.default.type` 이 설정돼 있지 않아(`KafkaConfig.java:119`), 헤더가 없으면 `DeserializationException` 이 되고 그 예외는 `addNotRetryableExceptions` 에 걸려 **재시도 없이 즉시 DLT** 로 간다.

### 15.2 부하 모델

```
baseline (드레인율 미만, 5분)  →  스파이크 (무페이싱 스텝)  →  주입 종료  →  lag 0
```

**스파이크는 페이싱하지 않는다.** 이 회차가 답할 질문이 "적체가 얼마 만에 빠지는가" 인데, 주입 종료 후 유입이 정확히 0이어야 lag 하강 기울기가 그대로 드레인율이 된다. 페이싱하면 회복 앞부분에 유입이 섞여 기울기가 혼탁해진다. 같은 이유로 **스파이크 시작과 동시에 baseline 을 끊는다**(스크립트를 순차 실행하면 자동으로 그렇게 된다).

**드레인율은 회복 구간의 하강 기울기로 직접 읽는다.** 별도 캘리브레이션 회차가 필요 없다.

**baseline 과 스파이크는 코호트 구간을 겹치지 않게 한다**(`OFFSET`). 같은 건을 두 번 쓰면 booking 이 이미 CONFIRMED 라 `confirm()` 이 no-op 이 되고 티켓도 `already_issued` 가 되어 유입이 조용히 깎인다.

### 15.3 절차

1. 터널·`IMAGE_TAG` 확인(§7.1, §8.4).

2. **시딩** — 규모는 `필요 건수 = baseline + 스파이크` 에 여유를 둔다.

   ```bash
   { printf "SET @i_confirm_loadtest_db=1, @mode='seed', @count=30000;\n"; \
     tr -d '\r' < load-test/seed/seed_payment_pipeline.sql; } \
     | ssh -i <key> ubuntu@<EC2_IP> \
       "docker exec -i ticketrush-mysql sh -c 'mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" ticket_rush'"
   ```

   검증 SELECT 의 **`contiguous_seats=1`, `seat_number_ordered=1`, `seat_booking_aligned=1`, `pending=@count`, `held=@count`, `expiry_safe=@count`** 를 눈으로 확인한다. 하나라도 어긋나면 진행하지 않는다 — 주입 스크립트가 `seat_id = SEAT_ID_MIN + idx` 로 값을 만들기 때문에 좌석 연속성이 깨지면 전건이 `BOOKING_SEAT_MISMATCH` 로 죽는다.

   출력의 `perf_id / booking_id_min / seat_id_min / user_id` 를 다음 단계 인자로 쓴다.

3. **스크립트 전송** — 워킹트리가 CRLF 라 그대로 넘기면 첫 줄부터 죽는다(§10.2).

   ```bash
   tr -d '\r' < load-test/chaos/inject-payment-confirmed.sh | ssh -i <key> ubuntu@<EC2_IP> 'cat > /tmp/inject.sh'
   ```

4. **1건 프리플라이트** — 추측하지 말고 실물을 확인한다.

   ```bash
   ssh ... 'BOOKING_ID_MIN=.. SEAT_ID_MIN=.. USER_ID=.. COUNT=1 bash /tmp/inject.sh'
   ```

   그 뒤 DB 에서 넷을 확인한다: `inbox` 에 `booking-group`·`ticket-group` 행 각 1건 / `ticket` 1건 / `booking` `CONFIRMED` / `seat` `SOLD`. 그리고 `kafka-topics.sh --list | grep dlt` 가 비어야 한다. **lag 이 0으로 떨어졌다는 것만으로는 처리됐다는 증거가 아니다** — 역직렬화 실패로 DLT 에 가도 lag 은 0이 된다.

5. **스모크(수치 폐기)** — 소량을 무페이싱으로 2~3회 넣어 드레인율이 고원에 도달할 때까지 돌린다. **기동 직후에는 JIT 워밍업 때문에 드레인율이 회차 중에도 계속 오른다**(실측: 1차 21/s → 3차 35/s). 워밍업이 안 끝난 상태로 본 회차를 돌리면 회복 곡선 앞부분이 오염된다. 스모크는 `ticketrush_ticket_issue_total` 시계열을 낳는 역할도 한다(Micrometer 지연 등록이라 첫 발급 전엔 시계열이 아예 없다).

6. **리셋 후 본 회차** — 매 회차 앞에 반드시 리셋한다(#496 이 이걸 빠뜨려 회차 하나를 폐기했다). `@mode='reset'` 은 티켓·inbox 를 지우고 booking 을 PENDING, seat 를 HOLD 로 되돌린다.

   ```bash
   ssh ... 'BOOKING_ID_MIN=.. COUNT=3000  OFFSET=0    RATE=10 bash /tmp/inject.sh'   # baseline
   ssh ... 'BOOKING_ID_MIN=.. COUNT=20000 OFFSET=3000 RATE=0  bash /tmp/inject.sh'   # 스파이크 + 드레인 대기
   ```

7. **드레인 완료 후** `@mode='verify'` 로 정합성을 본다. 기대: `confirmed = sold = tickets = 주입 건수`, `stray_events = 0`.

8. 증적 기록(§10.5 구성) → 다음 회차면 리셋 → EC2 중지.

### 15.4 PromQL

| 축 | 쿼리 |
|---|---|
| **컨슈머 랙(총 적체)** | `sum by (instance, topic) (kafka_consumer_fetch_manager_records_lag{job="ticketrush-services", topic="payment-confirmed-topic"})` |
| 컨슈머 랙(파티션 스큐) | `max by (instance, topic) (kafka_consumer_fetch_manager_records_lag{job="ticketrush-services", topic="payment-confirmed-topic"})` |
| 발급 처리율 | `sum by (result) (rate(ticketrush_ticket_issue_total[1m]))` |
| Inbox 중복 차단 | `sum by (consumer_group, result) (rate(ticketrush_kafka_inbox_total[1m]))` |
| DB 대기 | `hikaricp_connections_pending{job="ticketrush-services"}` |
| 호스트 CPU | `100 * (1 - avg(rate(node_cpu_seconds_total{job="node", mode="idle"}[1m])))` |

> ⚠️ 라벨 축은 `instance` 다(`ticket-service:8090`, `booking-service:8090`). `application` 라벨은 이 스택에 없다(§11.6).

### 🚨 `kafka_consumer_fetch_manager_records_lag` 로 backlog 곡선을 그리지 않는다

**#504 실측에서 확인했다.** 이 지표는 파티션별로 **"마지막 fetch 응답 시점의 lag"** 이다. `max.poll.records=20` 으로 파티션 3개를 훑으면 파티션마다 갱신 시각이 달라지고, `sum by (instance)` 는 **신선한 값과 낡은 값을 더한다.** 그 결과 적체가 단조 감소하지 않고 톱니로 튄다. (#504 당시는 단일 스레드가 세 파티션을 번갈아 훑는 조건이었다. #596 이후 스레드가 파티션당 하나씩 붙으면 갱신 시각이 덜 벌어져 톱니 진폭은 줄 수 있지만, 파티션별 스크랩 시점 차이는 남으므로 지표 성질 자체는 같다.)

```
booking-service:8090   4,632 → 4,052 → 6,073 ↑ → 5,473          (Prometheus 15초, max by)
ticket-service:8090    4,692 → 6,033 ↑ → 4,713 → 6,248 ↑        (Prometheus 15초, max by)
                       ... 이후 3,919 에서 3표본(45초) 정체 — 그 파티션이 fetch 되지 않았다
```

**유입이 이미 끝난 회복 구간인데 값이 다시 올라간다.** 같은 구간을 브로커에서 5초로 뜬 값은 표본 47개에서 **단조 감소 위반이 0회**였다(정점 19,870 → 0).

| 무엇을 | 어디서 |
|---|---|
| 적체 절대량·회복 곡선·드레인율 | **브로커** — 주입 스크립트의 `[drain]` 루프(`kafka-consumer-groups --describe` = `LEO − committed`) |
| 파티션 분포(스큐) | **브로커** — 파티션별 `LOG-END-OFFSET` |
| 처리율·자원 축 | **Prometheus** — `ticketrush_kafka_inbox_total`·`ticketrush_ticket_issue_total`·CPU·HikariCP·톰캣(서버 카운터라 스크랩 시점 문제 없음) |
| `records-lag` 패널 | **"랙이 있다/없다" 신호로만.** 절대량·곡선 판단 금지 |

§10.3 과 Grafana `Kafka Consumer Lag` 패널이 이 지표를 쓴다 — **컨슈머가 여러 파티션을 한 스레드로 훑는 구성에서는 그 패널을 적체량으로 읽지 않는다.**

### 15.5 주의 / 무효 판정

아래가 하나라도 어긋나면 회차를 폐기한다.

| 항목 | 기준 | 왜 |
|---|---|---|
| DLT 토픽 | `payment-confirmed-topic.DLT` 미생성 | 생기면 유선 형태가 틀렸거나 일시 실패가 났다는 뜻 |
| `stray_events` | 0 | `SeatConfirmFailedEvent`·`BookingExpiredEvent` 가 나오면 좌석 1:1 이 깨졌거나 만료 스케줄러가 코호트를 물었다 |
| `confirmed = sold = tickets` | 주입 건수와 일치 | 어긋나면 유입이 깎였다(eventId 중복 / OFFSET 겹침) |
| inbox `duplicate` 증가 | 0 | 완료조건 2 |
| `[CRITICAL]` 로그 | 0건 | `BOOKING_EXPIRED`·`BOOKING_CONFIRM_NOT_ALLOWED`·`BOOKING_SEAT_MISMATCH` 는 전부 `EXPECTED_CONFLICTS` 밖이라 CRITICAL 로 찍힌다 |

**DLT 백오프가 회복시간을 오염시킨다.** `ExponentialBackOffWithMaxRetries(5)`, initial 1s, ×2, max 60s → 일시 실패(seat-service 5xx·타임아웃) 1건마다 **그 파티션이 약 31초 멈춘다.** 회복 곡선이 계단식으로 튀면 그건 드레인율이 아니라 백오프다.

**`auto.offset.reset=latest` 함정.** 컨슈머가 붙기 전에 주입하면 그 분량을 통째로 건너뛴다. 스크립트의 preflight 가 두 그룹의 토픽 구독을 먼저 확인한다.

**`verify-inbox.sql` 의 한계를 리포트에 명시한다.** 완료조건은 "티켓 이중 발급 0건을 `verify-inbox.sql` 로 검증"이라고 적었는데, 그 쿼리(`ticket GROUP BY booking_id HAVING COUNT(*) > 1`)는 **`ticket.booking_id` 가 UNIQUE 제약이라 구조적으로 항상 0행**이다. 증적은 되지만 스스로 아무것도 증명하지 않는다. 실제 중복 유입이 흡수됐다는 근거는 `ticketrush_kafka_inbox_total{result="duplicate"}` 와 `ticketrush_ticket_issue_total{result="already_issued"}` 쪽이다.

**실효 드레인율의 상한 요인**을 리포트에 함께 적는다: 파티션 수, 그룹당 컨슈머 수(= 소비 병렬도), `max.poll.records=20`, `fetch.max.wait.ms=500`(`KafkaConfig` 상수).

---

## 16. 대기열 유입 제어 측정 (#472)

대기열([ADR 0009](adr/0009-virtual-waiting-room-with-server-directed-polling.md))이 실제로 유입을 흡수하는지 확인하는 회차다. 회차 A와 B의 **순서가 설계에 걸려 있다** — A가 확정한 `R`이 B의 입력값이다.

### 16.1 이슈 서사와 실측의 차이 — 먼저 읽을 것

**`R`(상태 확인 경로가 감당하는 RPS)은 존재하지 않는 수치다.** [#470](https://github.com/TicketRush/TicketRush-backend/issues/470)은 폴링 주기 하한을 "#348에서 실측한 폴링 경로 무릎"으로 역산하라고 적었지만, **#348은 회선 제약(좌석맵 응답 221 KB, 압축 없음)에 막혀 앱의 무릎에 도달조차 못 했다.** ADR 0009 §3이 이 사실을 명시하고 임시값 `R ≥ 400`을 쓴다 — #529 `seat-counts` 포화점 396.75 RPS를 빌린 보수적 하한이다.

**회차 A의 산출물이 그 수치다.** 여기서 `R`을 재고 `T = ceil(10000 / R)`로 폴링 주기를 확정한 뒤, ADR 0009 §3을 갱신하고 상태를 "제안됨" → "승인됨"으로 정정한다.

**ADR 0009 §5는 이미 틀린 것이 확인됐다.** "keep-alive 타임아웃을 짧게 둔다"고 적었지만 게이트웨이 8080은 `127.0.0.1`로만 publish되고, `deploy/nginx/api.ticketrush.store.conf`의 `location /`은 upstream 블록 없이 `proxy_pass` 직접이라 **`keepalive` 지시자가 구조적으로 부재**하다. nginx는 게이트웨이 커넥션을 재사용하지 않으며, 1만 개의 유휴 커넥션은 게이트웨이가 아니라 **nginx가 쥔다.** 게이트웨이 netty 튜닝은 효과가 없고 실제 상한은 nginx `worker_connections × worker_processes`다. 방향(단축/유지)은 회차 A의 A/B로 가른다.

### 16.2 부하 모델

| 프로파일 | executor | 무엇을 재나 |
|---|---|---|
| `status` | `ramping-arrival-rate` 계단 `200 → 400 → 800 → 1600` × 5분 | **`R`.** 상태 확인 경로 단독. 사용자 행동이 아니라 경로 용량이라 sleep이 없다 |
| `flood` | `ramping-vus` → 10,000 | **유입 제어.** 진입 → 서버 지시 폴링 → 입장 → 예매의 전 여정 |

계단 시작점이 200인 근거: 이 경로는 `GET`(대기 토큰) + `ZRANK` 두 번이고 **DB를 타지 않는다.** 게이트웨이 홉 + DB 집계를 포함한 #529의 396.75 RPS보다 빠를 수밖에 없으므로 그 위에서 시작한다.

**서버 지시 폴링은 이 저장소에 선례가 없는 패턴이다.** k6가 `sleep(고정값)`이 아니라 응답의 `next_poll_after_seconds` + 지터(±20%)로 잔다. 응답이 깨졌을 때의 폴백(`QUEUE_FALLBACK_POLL=25`)이 안전장치가 아니라 **필수**다 — 값이 없거나 0으로 떨어지면 k6가 sleep 없이 재폴링해 스스로 DDoS가 되고, 하필 대상이 죽어가는 구간에서 정확히 그렇게 된다.

### 16.3 시딩

**DB 시딩이 필요 없다** — 대기열은 Redis만 쓴다. 다만 두 가지 준비가 있다.

1. **`flood`의 마지막 단계인 예매는 DB를 탄다.** `seed_load.sql`의 좌석 풀이 필요하고, VU 수만큼 좌석을 비가역 소모한다.
2. **`user_id` 코호트.** k6가 게이트웨이와 같은 시크릿으로 JWT를 직접 서명한다(`-e QUEUE_JWT_SECRET=...`). 1만 번 로그인은 bcrypt(cost 10) 비용이 2 vCPU를 통째로 먹어 auth-service를 재게 되므로 택하지 않았다. **다만 `flood`의 예매가 실제 `user_id`를 요구하므로 코호트 규모만큼 계정이 시딩돼 있어야 한다** — `seed_load.sql`은 현재 계정 하나만 만든다. 필요 규모는 회차 A 결과에 달려 있어 측정 이슈에서 확정한다.

`status` 회차는 `setup()`이 `QUEUE_PRELOAD_SIZE`(기본 10,000)명을 진입시켜 ZSET을 채운다. **빈 대기열을 재면 안 된다** — `ZRANK`는 skiplist 탐색이라 크기에 로그 비례하고, 낙관적인 `R`은 그대로 폴링 주기 하한이 되어 운영에서 터진다. 폴링에 쓰는 토큰은 마지막 진입자 것이라 순번이 가장 뒤 = 탐색 비용이 가장 큰 지점이다.

### 16.4 절차

**사전 게이트 — 하나라도 어긋나면 시작하지 않는다.**

| # | 항목 | 확인 |
|---|---|---|
| G0 | 대상 EC2 기동 · `IMAGE_TAG` 기록 | `docker ps --format '{{.Names}}\t{{.Image}}'`. **`.env`의 `IMAGE_TAG`를 믿지 말고 실제 실행 중인 태그를 읽는다** — 아래 참조 |
| G1 | **nginx `worker_connections` 실측** | `nginx -T \| grep -E 'worker_connections\|worker_processes'`. 기본값 1024 × 2 ≈ 2,048이면 1만 VU에 못 미친다 → 16384 + `worker_rlimit_nofile` 상향. **미조치 회차는 무효** |
| G2 | `QUEUE_ENABLED=true` | `deploy/.env` 확인 후 **`docker compose up -d --force-recreate --no-deps gateway-service`**. ⚠️ `restart`는 `env_file`을 다시 읽지 않아 값이 반영되지 않는다(#549·#554 실측). 반영 여부는 `docker inspect gateway-service`의 `Config.Env`로 확인한다 |
| G3 | 대기열 지표 노출 | `curl -s localhost:8090/actuator/prometheus \| grep ticketrush_queue` — 미발생 상태에서도 0으로 보여야 한다 |
| G4 | Redis 여유 | `redis_memory_used_bytes` < 48 MB. `maxmemory 64mb` + `noeviction`이라 상한에 닿으면 좌석 락 SET까지 거절된다 |
| G5 | Prometheus targets | `job='gateway'` up |
| G6 | 생성기 EC2 | [ADR 0010](adr/0010-in-aws-load-generator-for-ten-thousand-vu.md) — 같은 리전, spot, 회차 후 **종료** |
| G7 | **대기열 키 리셋** | `redis-cli --scan --pattern 'queue:*' \| xargs -r redis-cli del` 후 0건 확인. **회차 A·B 양쪽 모두 필수** — 이전 회차의 `queue:opened-at:{pid}`(TTL 6h)가 남아 있으면 `threshold = 경과 × rate` 가 이미 수십만이라 전원이 첫 폴링에서 즉시 승급한다. "유입 제어가 되는가"를 보려던 회차가 그냥 스파이크가 된다 |

> **⚠️ 유효한 `.env`는 `~/ticketrush/deploy/.env` 하나다.** `cd.yml`이 `docker compose --env-file deploy/.env`로 실행하므로 그것만 읽힌다.
>
> 2026-08-13(#554) 이전에는 `~/ticketrush/.env`가 함께 있었고 그 안의 `IMAGE_TAG`는 `6b7301a0…`이었다. 2026-07-31 회차 B 준비 중 "`.env`가 ECR·CD기록·저장소 어디에도 없는 태그를 가리킨다"고 기록된 그 값이 **바로 이 잔재 파일이었다** — 불일치가 아니라 **엉뚱한 파일을 본 것**이다. #554에서 참조가 없음을 확인하고 삭제했으므로 이제 헷갈릴 파일이 없다.
>
> 과거 `.env` 백업들도 같은 회차에서 아카이브로 묶었다(`deploy/env-backups-archive-20260813.tar.gz`, `env-legacy-root-20260813.tar.gz`). **새 백업을 만들면 회차가 끝난 뒤 지우거나 아카이브에 넣는다** — 시크릿이 든 파일이 디렉토리에 쌓이면 어느 것이 현재인지 다시 헷갈린다.
>
> 그래도 **G0에서 실행 중 태그를 직접 읽는 원칙은 유지한다.** EC2 재시작은 기존 컨테이너를 되살릴 뿐 `.env`를 다시 읽지 않으므로, 파일 값과 실행 값이 갈릴 경로는 여전히 남아 있다.
>
> **그대로 `docker compose up -d`를 하면 없는 이미지를 당기다 그 서비스가 내려간다.** 회차 중 `--force-recreate`가 필요한 절차(`QUEUE_ENABLED` 반영 등)가 있으므로, **G0에서 실행 중 태그를 읽어 `.env`를 먼저 맞춘 뒤** `metadata.txt`에 기록한다.
>
> **설정 차이는 재배포가 아니라 `.env`로 메울 수 있는지 먼저 본다.** 같은 날 실측에서 배포본(`21d2da2d`)과 `develop` 사이의 **프로덕션 코드 차이는 `gateway-service/.../application.yml` 한 파일**뿐이었다(변경 파일 122개 중 나머지는 전부 증적·문서). 회차 A가 갱신한 `status-rps-capacity` 기본값 400 → 1,400이 그 내용이고, 이 값은 `${QUEUE_STATUS_RPS_CAPACITY}`로 열려 있다 — **`.env`에 `QUEUE_STATUS_RPS_CAPACITY=1400`을 넣으면 CD 약 20분 없이 같은 상태가 된다.** 재배포는 프로덕션 코드가 실제로 바뀌었을 때만 한다(#512 "배포는 묶어서 1회").
>
> **회차 A는 `QUEUE_ADMIT_RATE` 를 낮춘다(권장 `1`).** 승급 임계치는 `경과 × admit-rate` 라 기본값 20이면 PRELOAD 1만 기준 **8분 20초에 폴링 대상이 승급해 버린다.** 회차가 20분(4계단 × 5분)이라 반드시 걸린다. 승급 후 폴링은 입장 토큰 `SET` 이 붙어 Redis 명령이 2회(`GET`+`ZRANK`)에서 3회로 늘고 `noeviction` Redis 에 쓰기까지 생긴다 — **재려던 "대기 중인 사용자의 폴링" 이 아니라 다른 경로를 재게 되어 `R` 이 과소평가된다.** `1` 이면 1만에 도달하는 데 2.8시간이라 회차 내내 대기 상태가 유지된다. 시나리오의 `queue_status_admitted_leak` 이 이 사고를 감지한다(§16.6).
>
> **대기열 개시는 시나리오의 `setup()` 이 한다** — `POST /api/v1/queue/{pid}/open`(ADMIN). 개시 시각을 진입의 부작용으로 두면 오픈 전에 미리 진입해 둔 사람이 임계치를 부풀려 대기열을 무력화할 수 있어서, 운영자만 심도록 되어 있다. **G7 리셋이 이 호출보다 먼저**여야 새 기준점이 잡힌다.

**회차 A (R 실측)**

```bash
# 생성기 EC2에서. QUEUE_JWT_SECRET 은 게이트웨이 jwt.secret 과 같은 값이며 커밋하지 않는다.
docker run --rm -v $PWD/load-test:/scripts:ro \
  -e K6_OUT=experimental-prometheus-rw \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://<EC2>:9090/api/v1/write \
  grafana/k6:latest run \
  -e BASE_URL=https://api.ticketrush.store -e QUEUE_PROFILE=status \
  -e QUEUE_PERF_ID=1 -e QUEUE_JWT_SECRET='...' \
  /scripts/scenarios/waiting-room.js
```

1. 리셋 — G7 (대기열 개시 시각까지 함께 지운다)
2. 스모크 — 수치 폐기 전제(§6.1 규칙 4)
3. 본 회차 — 계단 유지 5분(Prometheus 15초 스크랩 × 20표본)
4. **nginx `keepalive_timeout` A/B** — 같은 계단을 `75s`(기본)와 `15s`로 각 1회. 호스트 CPU·메모리·`k6_http_req_connecting_p95`로 §16.1의 판단을 가른다
5. 판정 — 도착률을 올려도 실제 RPS가 안 오르는 구간 + `dropped_iterations` + 호스트 CPU 수렴 → 그 값이 **R**
6. 반영 — `queue.status-rps-capacity` 교체, **ADR 0009 §3·§5 갱신 + 상태 승인됨**

**회차 B (1만 VU 유입 제어)** — A에서 확정한 `R`을 반영한 배포본으로 `QUEUE_PROFILE=flood`.

완료 조건의 핵심 축은 하나다: **`POST /api/v1/booking`의 서버 RPS가 `queue.admit-rate-per-second`(기본 20) 부근에서 평평할 것.** 유입 1만 VU와 무관하게 평평해야 ADR 0009 "결과"의 첫 줄이 수치로 확인된다. 부수 확인은 `ticketrush_queue_waiting` 단조 감소, `queue_wait_to_admit_seconds` p95, 오버셀 0행(#344 검증 SQL).

> **⚠️ `R`을 저부하에서 재서 폴링 주기에 쓰면 회차가 무효가 된다(#549 B-1).** `R`은 상수가 아니라 **동시 커넥션 수의 함수**다 — 같은 경로·호스트·이미지에서 커넥션 약 1,600(#546)일 때 1,400 RPS, 16,701(#549 B-1)일 때 **450 RPS**로 3.1배 차이났다. `T = ceil(N/R)`이 한산할 때 잰 `R`을 쓰면 **정확히 붐빌 때 주기를 너무 짧게 지시**해 대기열이 스스로를 무너뜨린다(수요 1,250 RPS vs 용량 450 RPS). `R = 400` · `T = 25초`로 되돌린 B-2가 유효 회차가 됐다. **`R`을 올리려면 올리려는 동시성에서 재야 한다.**
>
> **`RedisCommandTimeoutException`을 Redis 장애로 읽지 않는다.** B-1에서 25만 줄이 찍혔지만 Redis 컨테이너 CPU는 3.89%였고 `slowlog`는 비어 있었다. Lettuce 타임아웃은 클라이언트 쪽에서 재는 값이라 **게이트웨이가 CPU를 못 잡으면 서버가 즉답해도 터진다.** 이 로그를 보면 먼저 `docker stats`와 `redis-cli slowlog`로 어느 쪽인지 가른다.
>
> **폴링 안전 상한 `QUEUE_MAX_POLLS`는 60 → 300으로 올렸다.** 60은 `T=25초` 기준이고, 대기 인원이 줄면 `T`가 하한 3초까지 내려가 같은 대기 시간에 폴링이 몇 배 더 필요해진다. 상한에 걸린 VU는 `queue_polls_exhausted`로 잡히는데, 그러면 **클라이언트가 먼저 포기한 것이 "입장 허용량이 유입을 소화하지 못했다"로 오독된다.** B-2 실측은 avg 15.9 / max 39회였다.

**B-1. 시딩** — 대기열은 Redis만 쓰지만 **여정의 마지막인 예매는 DB를 탄다.** `BookingValidateReferencesUseCase`가 `user` 행과 `(seat_id, performance_id)` 쌍을 요구하므로 코호트 규모만큼 둘 다 있어야 한다. `seed_load.sql`은 계정 1개 + 공연당 600석이라 못 쓴다.

```bash
# EC2 에서. 가드 변수가 없으면 본문이 돌지 않는다(seed_load.sql 과 같은 규율).
docker exec -i ticketrush-mysql mysql -uroot -p"$PW" ticket_rush \
  --init-command="SET @i_confirm_loadtest_db=1" < load-test/seed/seed_queue_flood.sql
```

끝에 출력되는 **`-e 인자` 4줄을 그대로 k6 실행에 넣는다**(`QUEUE_PERF_ID` · `QUEUE_USER_ID_MIN` · `QUEUE_SEAT_ID_MIN` · 코호트 크기). 오프셋이 `MIN(id) - 1`인 것은 시나리오가 `id = 오프셋 + 1 + exec.scenario.iterationInTest`로 매기기 때문이다 — **#555 에서 `exec.vu.idInTest`(1-base)에서 `iterationInTest`(0-base)로 바꿨다.** `ramping-arrival-rate`는 VU 를 재사용하므로 VU 번호로 사람을 매기면 같은 계정·좌석이 여러 번 나온다. 오프셋 값 자체는 바뀌지 않고 0-base 보정을 시나리오가 한다.

**연속성이 `GAP`이면 그대로 쓰지 않는다.** 구멍에 걸린 VU의 예매가 404로 튕겨 예매 경로 RPS가 과소 집계된다 — `cleanup_load.sql` 후 재시딩한다. `prod`는 `seat_id`가 이미 13만대까지 소진돼 있어 좌석과 계정의 번호대가 겹치지 않는다. **오프셋 없이 돌리면 회차 전체가 404다.**

**B-2. 생성기 EC2** — [ADR 0010](adr/0010-in-aws-load-generator-for-ten-thousand-vu.md). 대상과 같은 리전·VPC, `m7i.2xlarge`(8 vCPU / 32 GiB) spot, Ubuntu 24.04, 키 페어는 대상과 같은 것을 재사용한다. 보안 그룹은 인바운드 SSH를 **내 IP에서만**.

사용자 데이터(기동 시 1회):

```bash
#!/bin/bash
apt-get update -y && apt-get install -y docker.io git
usermod -aG docker ubuntu
sysctl -w net.ipv4.ip_local_port_range="10000 65535"
echo "* soft nofile 1048576" >> /etc/security/limits.conf
echo "* hard nofile 1048576" >> /etc/security/limits.conf
docker pull grafana/k6:latest
```

**포트 범위와 fd 상한이 없으면 1만 커넥션에서 생성기가 대상보다 먼저 고갈된다**(기본 ephemeral 포트 약 28,000 · `nofile` 1,024). 적용 여부는 `ulimit -n`과 `sysctl net.ipv4.ip_local_port_range`로 회차 전에 확인한다.

**B-3. 관측 터널** — 대상의 Prometheus는 `127.0.0.1` 바인딩이라(ADR 0007) 생성기에서도 터널이 필요하다. 대상 키를 생성기로 옮긴 뒤 백그라운드 터널을 연다. 대상 보안 그룹이 IP 제한이면 생성기 사설 IP의 22번을 열어야 한다.

```bash
chmod 600 ~/target.pem && ssh -f -N -L 9090:localhost:9090 -i ~/target.pem ubuntu@<대상사설IP>
```

**B-4. 실행**

**생성기가 Linux일 때**(원래 이 절이 전제한 구성):

```bash
docker run --rm --network host --ulimit nofile=1048576:1048576 \
  -v $PWD/load-test:/scripts:ro \
  -e K6_OUT=experimental-prometheus-rw \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
  grafana/k6:latest run \
  -e BASE_URL=https://api.ticketrush.store -e QUEUE_PROFILE=flood \
  -e QUEUE_FLOOD_RAMP=5m \
  -e QUEUE_PERF_ID=<시딩값> -e QUEUE_USER_ID_MIN=<시딩값> -e QUEUE_SEAT_ID_MIN=<시딩값> \
  -e QUEUE_JWT_SECRET='...' \
  /scripts/scenarios/waiting-room.js
```

`--network host` 없이는 터널의 `localhost:9090`에 닿지 못하고, 컨테이너 NAT이 커넥션 상한을 한 겹 더 만든다.

> **⚠️ 생성기가 Windows(Docker Desktop)면 위 명령을 그대로 쓰지 않는다.** `--network host`가 동작하지 않는다. `--network host`를 빼고 remote-write 주소를 **`host.docker.internal:9090`**으로 바꾼다 — 터널이 `127.0.0.1:9090`에만 바인딩돼 있어도 컨테이너가 이 이름으로 호스트에 닿는다. #549와 #554가 실제로 쓴 형태이고, `k6-summary-*.txt`의 `output:` 줄이 그 증거다. Git Bash라면 `-v` 경로가 자동 변환되므로 `MSYS_NO_PATHCONV=1`을 앞에 붙인다.
>
> **⚠️ `-e QUEUE_FLOOD_RAMP=5m`을 빠뜨리지 않는다.** 기본값은 `2m`이고(`config/env.js`), **램프 2분이 곧 #549 B-1을 무효로 만든 조건이다.** 2026-08-13 이전의 이 절 명령줄에는 이 인자가 없었다 — 그대로 복사하면 B-1 형상이 재현된다. 주입 여부는 실행 전 `k6 inspect`의 `stages`로 확인한다.

**⚠️ `deploy/.env`에서 `QUEUE_ADMIT_RATE=1`을 지우고 20으로 되돌린다.** 회차 A가 남긴 값이고(폴링 대상의 승급을 막는 조치였다), 이 회차는 **승급 자체가 관측 대상**이다. 1로 두면 1만 명이 다 들어가는 데 2.8시간이 걸려 회차가 성립하지 않는다 — `.env`에 남아 있는지 눈으로 확인한다. 2026-07-31 회차 B 준비 시점에 실제로 남아 있었다.

**B-5. 정리** — `cleanup_load.sql`(LTQ 코호트 블록 포함) 실행 → `queue:*` 리셋 → `QUEUE_ENABLED=false` 복구 → **생성기 terminate**. 마지막 항목이 빠지면 24/7 과금된다.

### 16.5 PromQL

`load-test/bench/dump-timeseries.py`의 `QUERIES`에 이미 들어 있다(`queue-*`, `booking-server-rps`, `redis-mem-used`, `k6-queue-*`). 회차 종료 후 1회 덤프하고 결과 디렉터리에 커밋한다.

| 축 | 쿼리 |
|---|---|
| 상태 확인 RPS | `sum(rate(ticketrush_queue_admission_total{job="gateway"}[1m]))` — 폴링 1회 = 카운터 1증가 |
| 입장 허용률 | `sum(rate(...{result="admitted"}[1m])) / sum(rate(...[1m]))` |
| 대기 인원 | `ticketrush_queue_waiting{job="gateway"}` |
| 폴링 주기 | `ticketrush_queue_poll_interval_seconds{job="gateway"}` |
| 예매 경로 RPS | `sum(rate(http_server_requests_seconds_count{instance="booking-service:8090"}[1m]))` |

게이트웨이의 `http_server_requests`는 uri 라벨이 `/**`·`UNKNOWN`으로 뭉개져(#402 실측 카디널리티 4) 폴링 경로를 따로 볼 수 없다. **위 커스텀 지표가 유일한 수단이다.**

### 16.6 주의 / 무효 판정

| 항목 | 기준 | 왜 |
|---|---|---|
| `queue_status_unavailable` | 0 | >0이면 fail-closed(ADR 0008)가 발동한 것이다. 대기열 성능이 아니라 Redis 장애를 측정한 회차다 |
| `ticketrush_queue_entry_token{result="unavailable"}` | 0 | 같은 이유 |
| nginx `worker_connections` | 실측·상향 완료 | 미조치면 앱이 아니라 nginx가 벽이다(§16.1) |
| `redis_memory_used_bytes` | < 48 MB | `noeviction` 상한에 닿으면 좌석 락 SET까지 거절돼 대기열 밖 경로가 함께 무너진다 |
| `dropped_iterations` | 해석 대상 | `status` 회차에서는 포화 신호이지 실패가 아니다 |
| `queue_polls_exhausted` | 0 | >0이면 입장 허용량이 유입을 소화하지 못했다 |
| `queue_booking_forbidden` | 0 | 승급 직후 예매라 0이어야 한다. >0이면 폴링→예매 지연이 입장 토큰 TTL(5m)을 넘었다 |
| `queue_admitted` | 해석 대상 | 0과 1 사이여야 한다. 정확히 1.000이면 지표가 아니라 스크립트를 의심한다 |
| **`queue_status_admitted_leak`** (회차 A) | **0** | >0이면 폴링 대상이 회차 도중 승급해 측정 경로가 2회에서 3회로 바뀌었다. `R` 을 재지 못한 회차이므로 **폐기하고 `QUEUE_ADMIT_RATE` 를 낮춰 다시 돈다** |
| 오버셀 검증 | §13.4-(7) **(a1)** | 이 회차는 VU 마다 좌석이 유일하다. 상태 필터 버전은 검출력이 0 이고(#554 §9.3), (a2) 는 #344 형 전용이다(#598). **SQL 원문을 `oversell-<arm>.txt` 에 함께 남긴다** |
| 생성기 종료 | 필수 | CD가 건드리지 않아 "떠 있는 줄 몰랐다"가 가능하다(ADR 0010) |

**§6.1 재현성 규칙이 여기서 한 겹 더 걸린다.** [ADR 0010](adr/0010-in-aws-load-generator-for-ten-thousand-vu.md)이 생성기를 로컬에서 AWS로 옮겼으므로 **이 회차 수치를 ADR 0004 토폴로지 회차(#348·#403·#529 등)와 절대값으로 직접 잇지 않는다.** 비교가 필요하면 그 사실을 리포트 한계에 적는다.

**15초 샘플러의 중앙값을 "최대"로 쓰지 않는다.** 묶음 C에서 두 번 틀렸다(#469 Redis 사용률 6.76 → 7.46%, #540 seat RSS 586 → 602 MiB). 시계열 덤프에서 max를 뽑거나 Grafana 캡처로 확인한다.

**회차 중에 대상 서버로 명령을 던지지 않는다 — 관측 행위가 관측값에 잡힌다.** 2 vCPU 호스트에서는 SSH 세션 자체, `docker stats`(컨테이너 16개 샘플링), `docker inspect`, Prometheus range query가 전부 호스트 CPU에 나타난다. 2026-08-13(#554) 무부하 기저 측정에서 이것만으로 **`max 57.55%`**가 찍혔다 — 같은 순간 `docker stats`의 컨테이너 CPU 합계는 13%, `load average`는 0.10이었다. 명령을 던지지 않은 구간에서는 기저가 **약 7%**로 일관됐다(min 6.63 / avg 7.03 / max 7.57).

> 관측은 k6 stdout과 **회차 후** 덤프로만 한다. `dump-timeseries.py`는 쿼리를 40개 넘게 던지므로 특히 회차 중에 돌리지 않는다(스크립트 docstring의 "부하가 도는 중에는 돌리지 않는다"가 같은 이유다). 상태를 꼭 봐야 했다면 그 사실과 시각을 증적에 적고 해당 구간을 해석에서 뺀다.

**회차 전 무부하 기저 CPU를 5분 재서 기록한다.** 배포본이 바뀐 뒤의 회차에서 "CPU가 높다"가 부하 때문인지 코드 드리프트 때문인지 가르는 유일한 수단이다. 위 함정 때문에 **그 5분 동안에도 명령을 던지면 안 된다.**
