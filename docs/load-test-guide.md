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

## 8. 동일 좌석 동시성 측정 (#344)

### 8.1 이슈 서사와 실측의 차이 — 먼저 읽을 것

이슈 #344의 제목은 "락 경합 차단율"이지만, **실측에서 `ticketrush_seat_lock_contention_total`은 0이다. 버그가 아니라 구조다.**

- 좌석 홀드는 HTTP API가 아니다. `SeatController`는 GET만 있고, 홀드 진입점은 `BookingCreatedEventListener`의 Kafka 리스너뿐이다. k6는 `POST /api/v1/booking`으로 **간접 유발만** 할 수 있고, 그 201 응답은 "예매 생성됨"이지 "좌석 선점됨"이 아니다.
- 그 리스너의 컨슈머 스레드는 **1개**다. `common/.../config/KafkaConfig.java`의 컨테이너 팩토리에 `setConcurrency`가 없고 `spring.kafka.listener.concurrency`도 어디에도 없어 기본값 1이 적용된다.
- Redisson `RLock`은 `(clientUUID:threadId)` 기준 **재진입** 락이다. 같은 스레드가 1,000건을 순차 처리하면 `tryLock`은 실패하지 않고 **재진입으로 성공**한다. 카운터는 `tryLock == false` 경로에서만 오르므로(`SeatLockUseCase`) 구조적으로 0이다.

**따라서 동시성 방어선은 락이 아니다.** 실제로 중복 선점을 막는 것은 둘이다.

1. `SeatHoldUseCase`의 `isAvailable()` 체크 → `ticketrush_seat_hold_total{result="unavailable"}`
2. `Seat.version` 낙관적 락(#427) → 커밋 시점 충돌 → 롤백 → Kafka 재시도로 수렴

**"차단율"은 §8.3의 `unavailable` 비율로 산출한다.** 락 경합률로 산출하면 항상 0%가 나와 방어선이 없다는 뜻으로 오독된다. 락은 정합성 장치가 아니라 경쟁을 앞단에서 걸러내는 **성능 최적화**이며, 정합성의 최종 방어선은 DB다 — 이 구분의 SSOT는 [ADR 0008](adr/0008-redis-spof-acceptance.md)과 `Seat.version` javadoc이다.

> 컨슈머 동시성을 올리면(`setConcurrency > 1`) 이 수치는 달라진다. 그때 락 경합이 실제로 설계대로 작동하는지는 `SeatHoldConcurrencyTest`(seat-service)가 스레드를 갈라 검증해 둔 상태다. oversell 0 자체의 증명도 같은 테스트가 실 MySQL로 수행한다(Redis 락을 제거한 두 번째 케이스).
>
> 참고로 `BookingCreatedEvent.key()`는 `bookingId`다. 파티션을 늘리는 순간 같은 좌석 이벤트가 여러 파티션에 흩어지므로, "파티션 순차 처리 덕분에 안전하다"는 서사는 성립하지 않는다. 지금 안전한 이유는 순전히 컨슈머 스레드가 1개이기 때문이다.

### 8.2 실행

**토폴로지는 §7을 따른다 — k6는 로컬, 대상 앱은 AWS 배포본이다([ADR 0004](adr/0004-load-test-execution-topology.md)).** 앱까지 로컬로 돌리는 "전부 로컬"은 ADR 0004가 **대안 1로 검토한 뒤 기각한 방식**이다(생성기와 대상이 CPU를 경쟁해 latency·throughput을 신뢰할 수 없다). 로컬 기동은 시나리오가 도는지 보는 기능 확인용으로만 쓰고, **거기서 나온 RPS·p99는 §8.4에 적지 않는다.**

`local` 프로파일 전용 DevToken(`/api/v1/dev/auth/token`)으로 인증을 우회하지 않는다 — AWS 배포본은 `prod` 단독이라 그 엔드포인트가 없고, ADR 0004가 부하 테스트에 쓰지 않기로 결정했다. write 시나리오에는 `LOAD_USER_PASSWORD` 평문이 필요하다.

사전 조건은 §7.1과 같다(EC2 기동, SSH 터널, AWS 부하테스트용 DB 시딩).

```bash
GUARD='SET @i_confirm_loadtest_db=1'
SEAT=1   # TARGET_SEAT_ID

# (1) 리셋 — 매 실행 전 필수. AWS 쪽 DB와 Redis 락을 함께 되돌린다.
#     좌석은 한 번 HOLD 되면 스스로 AVAILABLE 로 돌아오지 않고, Redisson 락 키는 성공 경로에
#     unlock 이 없어 TTL 5분간 살아남는다. DB만 되돌리면 2회차 홀드가 락 획득에 실패해
#     전부 보상 처리되어 측정이 무의미해진다.
mysql -h <AWS_DB_HOST> -u "$MYSQL_USERNAME" -p"$MYSQL_PASSWORD" \
  --init-command="$GUARD" ticket_rush -e "
    DELETE FROM booking WHERE seat_id = $SEAT;
    UPDATE seat SET seat_status='AVAILABLE', booking_number=NULL, hold_expired_at=NULL
     WHERE seat_id = $SEAT;"

# Redis 는 배포본 compose 안에 있으므로 EC2 에서 지운다(로컬 redis 가 아니다).
ssh -i <key>.pem <user>@<EC2_IP> \
  'cd <배포경로> && docker compose exec -T redis redis-cli DEL "seat:lock:1"'

# (2) 부하 — 짧고 날카로운 버스트. 전용 파라미터 없이 기존 VUS/RAMP/STEADY 로 프로파일을 만든다.
#     -e 위치 규칙과 --no-deps 는 §7.2 와 동일하다.
docker compose run --rm --no-deps \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://<PROM_HOST>:9090/api/v1/write \
  k6 run \
  -e BASE_URL=https://<aws-gateway> \
  -e PERF_ID=1 -e LOAD_USER_PASSWORD='<평문>' \
  -e TARGET_SEAT_ID=$SEAT -e VUS=200 -e RAMP=5s -e STEADY=30s \
  /scripts/scenarios/seat-contention.js

# (3) oversell 검증 — 반드시 1
mysql -h <AWS_DB_HOST> -u "$MYSQL_USERNAME" -p"$MYSQL_PASSWORD" \
  --init-command="$GUARD" ticket_rush \
  -e "SELECT COUNT(*) FROM seat WHERE seat_id=$SEAT AND seat_status='HOLD';"
```

> Windows Git Bash 에서는 `/scripts/...` 가 `C:/Program Files/Git/scripts/...` 로 치환된다. 경로 앞에 `//` 를 붙이거나(`//scripts/scenarios/seat-contention.js`) `MSYS_NO_PATHCONV=1` 을 준다. PowerShell 은 그대로 쓴다.

### 8.3 PromQL

부하 종료 후 Grafana Explore(§5)에서 실행한다. 측정 창 `[5m]`은 실제 실행 길이에 맞춘다.

| 지표 | 쿼리 |
|------|------|
| **oversell** (좌석 1개 부하에서 정확히 1) | `sum(increase(ticketrush_seat_hold_total{result="success"}[5m]))` |
| **차단율** (실측 방어선) | `sum(increase(ticketrush_seat_hold_total{result="unavailable"}[5m])) / sum(increase(ticketrush_seat_hold_total[5m]))` |
| **홀드 성공률** | `sum(increase(ticketrush_seat_hold_total{result="success"}[5m])) / sum(increase(ticketrush_seat_hold_total[5m]))` |
| **락 경합** (0 예상 — §8.1) | `sum(increase(ticketrush_seat_lock_contention_total[5m]))` |
| **처리량 (RPS)** | `sum(rate(k6_http_reqs_total[1m]))` |
| **p99 latency (생성기)** | `k6_http_req_duration_p99` |
| **p99 latency (앱)** | `histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{uri="/api/v1/booking"}[1m])) by (le))` |
| **컨슈머 소화율** | `sum(rate(ticketrush_kafka_inbox_total{result="processed"}[1m]))` |
| **파이프라인 적체** | `ticketrush_outbox_backlog` |
| **현재 HOLD 총수** | `ticketrush_seat_held` |

HTTP 사전 차단율(409)은 k6 요약의 `seat_conflict` Rate를 그대로 읽는다(전체 요청이 분모인 비율이다). `seat_accepted`도 같은 분모라 둘을 나누지 않는다 — 201 비율은 `seat_accepted`를 따로 읽는다.

> 차단율 분모에 `seat_lock_contention`을 넣지 않는 이유는 §8.1이다. 두 카운터는 분모가 다르다 — `seat_hold_total`은 DB 체크에 **도달한** 시도만, contention은 도달조차 못 한 시도만 센다.

### 8.4 측정 결과

| 항목 | 값 |
|------|-----|
| 실행 일시 / 커밋 | |
| 프로파일 (VUS/RAMP/STEADY) | |
| 총 요청 / 201 / 409 | |
| **oversell** | |
| 차단율 (unavailable / total) | |
| 홀드 성공률 | |
| 락 경합 | |
| RPS / p99(k6) / p99(앱) | |
| Grafana 캡처 | |
