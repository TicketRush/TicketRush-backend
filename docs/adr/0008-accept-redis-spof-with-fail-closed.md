# 8. Redis 단일 인스턴스 SPOF를 수용하고, 장애 시 전면 차단(fail-closed)한다

날짜: 2026-07-16

## 상태

승인됨

[ADR 0006](0006-eight-gib-container-memory-limits.md)의 메모리 예산과 [ADR 0007](0007-observability-stack-colocation.md)의 관측 스택 배치를 전제로 한다.

## 맥락

`deploy/docker-compose.prod.yml`의 Redis는 컨테이너 **1개**다. 복제도, Sentinel도, Cluster도 없다. 단일 장애점(SPOF)이다.

**Redis가 떠받치는 것**은 캐시만이 아니다.

| 용도 | 위치 | 성격 |
|---|---|---|
| 좌석 Redisson 락 | `SeatLockUseCase.java:28-32` | 정합성 자산 |
| 예매번호 SETNX 멱등키 | `BookingIssueNumberUseCase.java:32` | 정합성 자산 |
| RefreshToken | auth-service | 정합성 자산 |
| 공연 목록 캐시 · ShedLock | performance-service | 최적화 |

앞의 셋이 인메모리 스토어 위에 있다는 것이 문제의 핵심이었다. 특히 prod Redis는 `appendonly yes`이지만 fsync 기본 정책이 `everysec`이라 **크래시 시 최대 1초치 쓰기가 유실된다.** 그 사이 걸린 락 키가 사라진 채 재시작되면 같은 좌석에 두 개의 HOLD가 성립할 수 있었다 — 좌석 중복 판매.

작용한 힘은 다음과 같다.

- **비용 제약.** [ADR 0006](0006-eight-gib-container-memory-limits.md)이 정한 8 GiB 단일 인스턴스 예산 안에서 운영한다. Sentinel HA는 최소 컨테이너 4개에 수백 MiB로 예산 밖이고, ElastiCache Multi-AZ는 인스턴스 비용이 별도로 붙는다.
- **장애 시에만 도는 코드는 검증되지 않는다.** 락 획득 실패 시 DB 낙관적 락으로 degradation하는 안이 논의됐다. 그러나 평소에 한 번도 실행되지 않는 경로는 정작 장애 순간에 두 번째 장애를 만든다.
- **정합성은 DB가 보장해야 한다.** 최종 방어선이 인메모리 스토어에 있는 구조 자체가 잘못이었다.

### 결정을 가능하게 한 전제 — #427

`Seat` 엔티티에 `@Version`을 추가하는 [#427](https://github.com/TicketRush/TicketRush-backend/pull/431)이 **이 결정의 전제 조건**이었고, 먼저 완료됐다(`Seat.java:113-115`).

이제 DB가 최종 방어선이므로 **Redis 상태와 무관하게 좌석 중복 판매가 불가능하다.** 컬럼 하나를 추가하는 비용으로 Redis HA보다 확실한 안전을 얻었다. 이 전제가 없었다면 SPOF 수용은 정합성 포기와 같은 말이었다.

### 검토한 대안

1. **Redis HA 도입(Sentinel / Cluster / ElastiCache Multi-AZ)** — 기각. 비용·예산 제약. 그리고 #427 이후로는 HA가 막아주는 것이 "가용성"뿐이며, 그 대가가 이 프로젝트 단계에서 균형이 맞지 않는다.
2. **락 실패 시 DB 낙관적 락으로 degradation(fail-open)** — 기각. 위에 적은 대로 검증되지 않는 경로이며, 이중 선점을 허용하는 순간 락의 존재 이유가 사라진다.
3. **SPOF 수용 + fail-closed + 값싼 완화책** — 채택.

## 결정

**Redis SPOF를 수용한다. 대신 Redis 장애 시 전면 차단(fail-closed)하고, 정합성은 DB로 내린다.**

Redis 장애의 결과는 **가용성 손실(예매 거절)이지 정합성 손실(중복 판매)이 아니다.** #427이 그것을 보장한다.

### 1. 락·멱등 경로는 fail-closed

Redis 불가 = 요청 거절 + 재시도 유도. degradation 경로를 만들지 않는다.

**예외는 공연 목록 캐시 하나뿐이다**(`CacheConfig.java`의 `FailOpenCacheErrorHandler`). 캐시는 정합성 자산이 아니라 지연 최적화이므로 fail-open이 맞다. 이 비대칭은 의도된 것이다.

### 2. HA는 도입하지 않는다

전 서비스가 단일 Redis 인스턴스를 공유한다.

### 3. 인증은 password만. TLS는 도입하지 않는다

`requirepass`를 적용하고 Redis 사용 4개 서비스(auth/booking/performance/seat)와 redis-exporter가 같은 `REDIS_PASSWORD`를 쓴다. Redisson은 별도 설정 없이 `spring.data.redis`를 그대로 물려받으므로 락 경로도 함께 인증된다.

**TLS 미도입 근거**: Redis는 `ports`를 publish하지 않아 단일 EC2의 docker bridge 네트워크 밖에서 도달할 수 없다. 이 위협 모델에서 통신을 도청할 수 있는 주체는 같은 호스트의 root뿐이고, **root라면 TLS 개인키도 함께 읽는다** — 실질 이득이 0이다. 반면 `requirepass`는 다르다. 같은 네트워크의 다른 컨테이너가 실수나 침해로 붙는 것을 막는 실질 이득이 있고, 비용은 설정 한 줄이다.

**password는 `ps`에 노출된다.** `--requirepass`가 `command`에 있어 redis-server의 프로세스 인자로 `ps`·`docker inspect`에 그대로 보인다. 없애려면 `redis.conf` 마운트가 필요한데, 같은 호스트에서 그 파일을 읽을 수 있는 주체는 어차피 `.env`도 읽으므로 위 위협 모델 안에서는 이득이 없다. 다만 "password를 걸었으니 호스트 접근자에게도 가려진다"는 뜻은 아니다. healthcheck는 예외로 `REDISCLI_AUTH` 환경변수를 써서 인자 노출과 경고를 함께 피한다.

`.env`에 `REDIS_PASSWORD`가 없으면 compose의 `:?` 보간이 **배포를 멈춘다.** 무인증 Redis가 조용히 뜨는 것이 가장 나쁜 실패 모드이기 때문이다. healthcheck도 종료코드가 아니라 `PONG` 응답 문자열을 확인해 false-healthy를 막는다. 다만 `:?`는 unset·빈 값만 거른다 — `.env.prod.example`의 `__REQUIRED__`를 **그대로 복사하면 배포는 성공한다.** 플레이스홀더 값을 실제 값으로 바꾸는 것은 여전히 사람의 몫이다.

### 4. `maxmemory 64mb` + `noeviction`을 명시한다

`mem_limit: 128m`만 있으면 **Redis는 자기 상한을 모른 채 커널 OOM killer에 프로세스째 죽는다.** 장애가 "명시적 거절"이 아니라 "돌연사"가 된다. SPOF를 수용하기로 한 이상 가장 흔한 사망 원인을 방치할 수 없다.

`maxmemory`가 있으면 상한 도달 시 `noeviction`이 **쓰기에 OOM 에러를 응답**하고 읽기는 계속된다 — 이 문서가 정한 fail-closed와 정확히 일치한다.

`noeviction`은 Redis 7 기본값이지만 **명시한다.** Redis가 들고 있는 것은 전부 정합성 자산이라 evict 대상이 될 수 없는데, 명시하지 않으면 "캐시니까 LRU 아니냐"가 리뷰마다 재발하고 누군가 바꿔도 근거가 남지 않는다. 설정 두 줄이 문서 역할을 한다.

**값은 상한의 50%인 `64mb`다.** `appendonly`가 켜져 있어 AOF rewrite는 fork를 하고, 최악의 CoW는 데이터셋 전체 복사다. 상한에 가깝게 잡으면(예: 96mb) 정작 데이터가 가득 찬 순간 데이터 + CoW + 클라이언트 출력 버퍼가 128m을 넘겨 **noeviction이 거절을 시작하기 전에 커널이 먼저 죽인다** — 이 결정이 없애려던 바로 그 실패 모드가 그대로 남는다. 여유를 절반 남기는 통상 권고를 따른다.

**`mem_limit: 128m`은 유지한다** — [ADR 0006](0006-eight-gib-container-memory-limits.md)의 실측 최대 사용량이 4.1 MiB(상한의 3.20%)로, 64mb 기준으로도 여유가 15배다.

**한도 임박도 알린다.** 한도에 닿아도 Redis는 살아 있어 `redis_up`은 1이다. 즉 다운 알림만으로는 "예매가 실패하는데 아무도 모르는" 상태가 새로 생긴다. exporter가 이미 내보내는 `redis_memory_used_bytes / redis_memory_max_bytes`로 80% 초과 5분 지속 시 별도 경고를 보낸다. 정상 트래픽에서 도달할 값은 아니다(RefreshToken은 사용자당 1키 + TTL, 예매번호 키는 10분 TTL이라 상한이 사용자 수에 묶인다) — 사고·버그 시나리오를 위한 그물이다.

### 5. 감지는 redis_exporter + Grafana Alerting → Slack

SPOF를 수용하는 이상 **죽었을 때 즉시 아는 것이 유일한 대응책이다.** 그런데 지금까지 Redis 지표를 긁는 주체가 없어 장애를 사후에 500 로그로만 인지했다.

`redis-exporter` 컨테이너(32 MiB)를 추가해 `redis_up`을 수집하고, Grafana Alerting이 `redis_up == bool 0`을 1분 유지 시 Slack으로 보낸다. exporter는 포트를 publish하지 않는다([ADR 0007](0007-observability-stack-colocation.md)). `depends_on`은 `service_started`다 — `service_healthy`로 걸면 정작 감지해야 할 상황(Redis 다운)에서 exporter 자신이 뜨지 못한다.

**`noDataState: Alerting`인 이유**: Redis가 죽으면 지표가 `redis_up=0`으로 오리라 기대하기 쉽지만, 실제로는 exporter가 죽은 Redis에 붙느라 느려져 **Prometheus 스크랩이 타임아웃하고 지표 자체가 사라지는** 경우가 있다(검증 중 관찰). 그때는 `NoData`가 되므로, 이 값이 `OK`였다면 정작 Redis가 죽은 순간에 침묵한다. exporter 자신이 죽어 지표가 끊기는 것도 "Redis 상태를 모르는" 상황이고, SPOF를 수용한 이상 모르는 것이 곧 위험이다.

**`== bool 0`인 이유**(실기기 검증에서 잡았다): 직관적인 `redis_up == 0`은 **정확히 뒤집혀 동작한다.** 필터형 표현식은 Redis 다운 시 값 `0`을 반환하는데 Grafana는 0을 "조건 거짓"으로 읽어 영영 발화하지 않고, 정상일 때는 결과가 비어 `NoData`로 오히려 깨운다. `bool` 수식어는 다운 시 `1`, 정상 시 `0`을 주므로 의도대로 동작한다. 파일만 읽고 통과시켰다면 "알림을 붙였다"고 믿은 채 실제로는 침묵하는 규칙을 배포할 뻔했다 — 알림 규칙은 반드시 실제로 죽여서 울리는 것까지 확인한다.

**Alertmanager 미도입 근거**: 규칙이 1개고 Grafana는 이미 떠 있다. Alertmanager가 주는 그룹핑·억제·silence는 규칙 1개에 가치가 없고, 대가는 컨테이너 하나와 라우팅 설정이다. 알림은 booking-service DLT가 쓰는 `SLACK_WEBHOOK_URL`을 재사용한다.

### 6. 사용자 안내는 경로별로 다르다

| 경로 | 클라이언트 | 동작 |
|---|---|---|
| HTTP (예매번호 발급 · 로그인 · 토큰 재발급) | Lettuce | `INFRA_503_001` **503 + "일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."** 종전에는 `Exception` 핸들러에 걸려 500 "관리자에게 문의"로 나갔다 |
| HTTP (좌석 확정 `SeatInternalController`) | Redisson | 같은 503. 단 **핸들러가 다르다**(아래) |
| HTTP (공연 목록) | Lettuce | 캐시 fail-open → 정상 응답 |
| Kafka (좌석 락) | Redisson | `@RestControllerAdvice`를 타지 않는다. `KafkaConsumerErrorPolicy`가 Redis 예외를 일시로 분류 → 재시도 → DLT → Slack. 사용자 측은 `BookingExpirationScheduler`의 기존 만료 안내를 탄다 |

**핸들러가 둘로 나뉜 이유**: Lettuce 예외는 Spring이 `RedisConnectionFailureException`(`DataAccessException` 계열)으로 변환해주지만, **Redisson은 Spring 예외 변환을 타지 않고 `RedisException`(단순 `RuntimeException`)을 그대로 던진다.** 하나의 핸들러로 둘 다 잡으려면 common에 Redisson 예외를 등록해야 하는데, **Redisson 의존성은 seat-service에만 있어** common에 등록하면 나머지 6개 서비스가 어드바이스 로딩에 실패해 기동하지 못한다. 그래서 Lettuce는 common의 `GlobalExceptionHandler`가, Redisson은 seat-service의 `SeatRedissonExceptionHandler`가 맡는다.

Kafka 경로에 별도 안내를 넣지 않은 것은 **메시지 보존(DLT) + 운영자 알림 + 사용자 측 자동 만료가 이미 붙어 있기 때문이다.**

`QueryTimeoutException`은 잡지 않는다. Redis 전용이 아니라 JDBC·JPA 쿼리 타임아웃도 이 예외로 변환되므로, 등록하면 common을 쓰는 **전 서비스의 DB 타임아웃 응답까지 조용히 503으로 바뀐다.** DB 타임아웃의 처리 방침은 이 문서의 범위가 아니다.

NOAUTH(비밀번호 오설정)도 503으로 잡지 않는다. `RedisSystemException`으로 도착해 500이 되는데, 그건 일시 장애가 아니라 설정 버그이므로 500이 옳다.

### 7. Lettuce 타임아웃을 1초로 자른다

**fail-closed는 '거절'이지 '무한 대기'가 아니다.** seat-service는 Lettuce 기본 60초를 그대로 쓰고 있었다. 좌석 락 경로는 Kafka 리스너 스레드에서 도므로, hang형 장애에 60초씩 물리면 poll이 밀려 **컨슈머 리밸런싱 루프**로 장애가 번진다. Redis 장애가 Redis에 머물지 않고 Kafka로 전이되는 것이다.

performance-service가 같은 이유로 이미 1초를 적용해뒀다(`application.yml:16-20`). seat-service에 같은 값을 넣는다.

**단, 이 값이 곧 실패까지의 벽시계 시간은 아니다.** Redisson 스타터는 `spring.data.redis.timeout`을 응답 타임아웃으로 매핑하지만 자체 `retryAttempts`(기본 3)·`retryInterval`(기본 1.5s)을 별도로 갖는다. 최악은 1초가 아니라 수 초다. 60초보다 훨씬 낫지만 "1초에 차단된다"고 읽으면 안 되며, 리밸런싱을 실제로 막는지는 hang형 장애를 재현해 리스너 체류 시간을 재봐야 확정된다 — 이번 검증에는 그 측정이 없다.

**트레이드오프**: 응답 타임아웃이 짧으면 부하·GC 스파이크에 unlock이 타임아웃날 수 있고, 그러면 좌석 락이 `LOCK_TTL_MINUTES`(5분)까지 잔존해 그 좌석이 5분간 잠긴다. performance-service가 ShedLock(1분) 기준으로 수용한 것과 같은 성질이되 잔존 시간이 5배다. 정합성은 깨지지 않고(#427의 DB `@Version`) 가용성만 손해라 수용한다.

## 결과

### 얻는 것

- **Redis가 죽거나 락 키를 잃어도 좌석 중복 판매가 없다.** #427의 DB `@Version`이 최종 방어선이고, Redis 락은 경쟁을 앞단에서 걸러 DB까지 안 가게 하는 **성능 최적화**로 역할이 정리됐다.
- 무인증 Redis가 사라졌다. password 누락 시 배포가 조용히 성공하지 못한다.
- 상한 도달이 돌연사(OOMKill)가 아니라 명시적 쓰기 거절이 된다.
- hang형 장애가 60초에서 1초로 잘려 Kafka 리밸런싱으로 번지지 않는다.
- Redis 다운을 1분 내 Slack으로 안다. 비용은 컨테이너 1개(32 MiB).
- Redis 장애 시 사용자가 "서버 에러, 관리자에게 문의"가 아니라 재시도 안내를 받는다.

### 감수하는 것

- **Redis 다운 = 예매 생성 전면 중단.** `BookingIssueNumberUseCase`가 Redis에 의존하므로 멈추는 것은 부가 기능이 아니라 핵심 기능이다. 이것이 수용한 가용성 손실의 정확한 범위다.
- **복구는 사람이 한다.** 자동 페일오버가 없다. 목표 복구 경로는 Slack 알림(1분 이내) + 수동 재시작이다.
- **replica가 없고 AOF뿐이다.** 컨테이너나 EBS가 손실되면 진행 중인 락이 전부 사라진다. 그래도 최악은 "사용자가 재시도해야 함"이다.
- **password 무중단 rotate가 불가능하다.** `requirepass`가 `command`에 있어 컨테이너 재생성이 필요하다.
- **네트워크 내부 통신은 평문이다.** 위 결정 3의 위협 모델 안에서만 유효하다.
- **인증 경로가 처음 실행되는 곳이 운영이다.** 로컬 Redis는 무인증이고 CI(`schema-validate.yml`)도 `local` 프로파일이라, prod yml의 password 배선은 **어떤 자동 검증도 통과하지 않는다.** 이번에는 수동 실기기 검증으로 메웠지만, 다음에 누가 `password` 키를 빠뜨려도 CI는 초록이다. `:?` 보간과 healthcheck가 배포 시점에 잡아주는 것이 유일한 그물이다.
- **알림 설정은 로컬에서 검증되지 않는다.** 로컬 스택에는 exporter가 없어 알림 프로비저닝을 `provisioning-aws/`로 분리했다(로컬에 두면 `redis_up`이 없어 상시 오탐한다). 대가로 이 파일은 배포본에서만 로드되므로, 문법 오류는 배포 시점에야 드러난다.
- **최초 적용 시 수동 선행 단계가 있다.** EC2의 `deploy/.env`는 리포에서 오지 않고 사람이 관리한다. 이 변경이 담긴 배포 **이전에** 운영자가 `REDIS_PASSWORD`를 추가하고, `SLACK_WEBHOOK_URL`이 비어 있지 않은지 확인해야 한다(Grafana가 이 값을 요구하게 됐다). 누락 시 `:?`가 배포를 중단시키므로 부분 적용 없이 깨끗하게 실패한다.
- **메모리 예산 영향**: 컨테이너 `mem_limit` 합계가 [ADR 0006](0006-eight-gib-container-memory-limits.md)의 6,528 MiB에서 **6,560 MiB**로 늘어난다(redis-exporter +32 MiB). 실측 인스턴스 메모리 약 7.6 GiB 대비 여유는 유지된다. ADR 0006 본문의 표는 그 시점의 기록이므로 수정하지 않고, 델타를 이 문서에 남긴다.

### 향후 주의점 — ElastiCache 전환 시

`SeatLockExpirationListener`는 Redis keyspace 만료 이벤트에 의존하는데, Spring의 `KeyspaceEventMessageListener`는 기동 시 `CONFIG SET`으로 `notify-keyspace-events`를 자동 설정한다. **관리형 Redis에서는 `CONFIG SET`이 막혀 있어 조용히 실패할 수 있으므로** 파라미터 그룹에서 미리 설정해야 한다.

### 재평가 트리거

| 트리거 | 재검토 대상 |
|---|---|
| Redis를 EC2 밖(ElastiCache 등)으로 이동 | TLS 도입 (위협 모델이 바뀐다) |
| 알림 규칙이 3개를 넘어 알림 피로 발생 | Alertmanager 도입 |
| 예매 중단의 실측 손실 > HA 비용 | Sentinel / ElastiCache Multi-AZ |
