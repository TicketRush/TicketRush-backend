# 9. 대기열을 게이트웨이 안의 모듈로 두고, 서버가 지시하는 폴링으로 1만 VU 유입을 제어한다

날짜: 2026-07-31

## 상태

승인됨

폴링 주기 `T`와 keep-alive 방향은 [#546](https://github.com/TicketRush/TicketRush-backend/issues/546) 실측으로 확정했다(2026-07-31). §3·§5가 그 수치로 갱신됐다.

입장 허용량 `admit-rate-per-second`는 [#555](https://github.com/TicketRush/TicketRush-backend/issues/555) 계단 실측으로 **20 → 12**로 내렸다(2026-08-14). §3.5가 그 근거이고, 그 회차가 **무릎을 만드는 것이 `admit` 자체가 아니라 좌석 SSE 팬아웃**임을 대조 회차로 확정했다.

[ADR 0004](0004-load-test-execution-topology.md)의 측정 토폴로지, [ADR 0006](0006-eight-gib-container-memory-limits.md)의 메모리 예산, [ADR 0007](0007-observability-stack-colocation.md)의 관측 스택 배치, [ADR 0008](0008-accept-redis-spof-with-fail-closed.md)의 Redis SPOF 수용을 전제로 한다.

## 맥락

프로젝트 목표는 "대규모 트래픽을 처리하는 MSA 티켓팅"인데, 배포 환경은 **단일 EC2 `m7i-flex.large`(2 vCPU / 7.6 GiB)로 고정**돼 있다([ADR 0006](0006-eight-gib-container-memory-limits.md)). 이 간극을 하드웨어가 아니라 아키텍처로 메우는 수단이 대기열이다.

### 실측이 말하는 것 — 유입을 그대로 받으면 어떤 벽에 먼저 닿는가

| 회차 | 무엇을 봤나 |
|---|---|
| [#344](https://github.com/TicketRush/TicketRush-backend/issues/344) | `POST /api/v1/booking` **단독** 258 RPS에서 호스트 CPU 99.87% |
| [#348](https://github.com/TicketRush/TicketRush-backend/issues/348) | 조회 축을 올리면 **회선이 먼저 찬다.** 좌석맵 응답 221 KB, 압축 없음 → 앱의 무릎에 도달조차 못 했다 |
| [#505](https://github.com/TicketRush/TicketRush-backend/issues/505) | gzip 적용으로 회선 제약 23배 완화. 그러자 다음 벽이 seat-service 컨테이너 OOM([#509](https://github.com/TicketRush/TicketRush-backend/issues/509))이었다 |
| [#509](https://github.com/TicketRush/TicketRush-backend/issues/509)·[#518](https://github.com/TicketRush/TicketRush-backend/pull/518) | OOM을 고치자 다음 벽은 **호스트 CPU 99.29%**. 스레드 포화보다 CPU 도달이 2분 30초 앞섰다 |

**세 회차가 같은 것을 가리킨다 — 2 vCPU 단일 인스턴스에서 수백 RPS가 상한이다.** 1만 명이 동시에 들어오면 어느 축을 최적화하든 CPU가 먼저 죽는다. "SLO 1,000 RPS"는 이 구성에서 물리적으로 불가능하며, 그 사실은 #348이 이미 수치로 확인했다.

### 작용한 힘

- **인스턴스를 바꾸지 않는다는 제약이 확정돼 있다.** 용량을 늘리는 것이 아니라 유입을 고르게 펴는 것만 남는다.
- **컨테이너 메모리에 신규 서비스를 세울 자리가 없다.** `deploy/docker-compose.prod.yml`의 `mem_limit` 합계가 7,264 MiB로 여유가 약 518 MiB다(ADR 0007 §5). 576 MiB급 서비스를 하나 더 세우면 예산을 넘긴다.
- **게이트웨이에는 Redis 의존이 없다.** `gateway-service/build.gradle`은 webflux + gateway-server + security만 쓴다. 대기열을 게이트웨이에 두려면 reactive Redis 의존을 새로 들인다.
- **Redis는 이미 SPOF이고 fail-closed다**([ADR 0008](0008-accept-redis-spof-with-fail-closed.md)). 대기열을 Redis에 얹으면 그 SPOF 위에 기능이 하나 더 올라간다.
- **과부하 시 우아한 거절은 [#423](https://github.com/TicketRush/TicketRush-backend/issues/423)(Gateway Rate Limit)이 담당한다.** 대기열은 거절이 아니라 순번화를 맡는다 — 둘은 겹치지 않는다.

## 결정

**우리는 대기열을 게이트웨이 안의 모듈로 두고, Redis sorted set으로 순번을 매기며, 서버가 폴링 주기를 지시해 상태 확인 부하를 통제한다.**

### 1. 배치 — 신규 서비스가 아니라 게이트웨이 모듈

메모리 예산에 신규 서비스 자리가 없다는 것이 1차 근거이고, 2차 근거는 경로 길이다. 입장 토큰 검증은 **모든 예매 요청이 지나는 관문**이므로 게이트웨이에서 끝내야 홉이 늘지 않는다. 별도 서비스로 두면 게이트웨이 → 대기열 서비스 → Redis로 홉이 하나 더 붙는다.

대가로 게이트웨이에 reactive Redis 의존이 추가된다. 게이트웨이가 죽으면 전부 죽는 것은 지금도 마찬가지지만, **장애 표면이 넓어지는 것은 사실이다.**

### 2. 순번 — Redis sorted set + 입장 토큰

- 대기 등록: `ZADD waiting:{performanceId} <타임스탬프> <userId>`
- 순번 조회: `ZRANK`
- 입장 허용: 선두 N명을 꺼내 입장 토큰을 발급하고 ZSET에서 제거

**예매 API는 유효 입장 토큰만 통과시킨다.** 그 결과 예매 경로의 부하가 유입 규모와 무관하게 "입장 허용량"으로 고정된다 — 이것이 이 설계의 전부다. 허용량은 위 실측(booking 단독 258 RPS에서 CPU 99.87%)의 아래로 잡는다.

**메모리 예산**: prod Redis는 `maxmemory 64mb` + `noeviction`이다. ZSET 엔트리 1만 개는 skiplist + dict 오버헤드를 넉넉히 잡아도 1 MiB 안쪽이라 예산 안이다. **다만 `noeviction`이라 상한에 닿으면 좌석 락 SET까지 거절된다**([ADR 0008](0008-accept-redis-spof-with-fail-closed.md)) — 대기열 키에 TTL을 붙여 공연 종료 후 잔류를 막는다.

### 3. 폴링 주기 — 서버가 지시하고, 대기 인원에 비례해 늘린다

상태 확인 응답에 **다음 폴링 시각**을 담아 서버가 주기를 지시한다. 클라이언트가 주기를 정하면 1만 명이 동시에 같은 주기로 때려 부하가 통제 불능이 된다. 클라이언트는 거기에 지터를 더해 동기화된 버스트를 깬다.

**하한 산식**

```
필요 상태확인 RPS = 대기 인원 N / 폴링 주기 T
=> T ≥ N / R      (R = 상태 확인 경로가 감당하는 RPS)
```

### ⚠️ `R`은 상수가 아니다 — 이 산식의 결함

**`T = ceil(N/R)`은 `R`을 상수로 쓰지만, `R`은 동시 커넥션 수의 함수다.** 같은 경로·같은 호스트·같은 이미지(`21d2da2d`)에서 실측한 두 점([#549](https://github.com/TicketRush/TicketRush-backend/issues/549), `load-tests/k6/results/260801-549-queue-flood/`):

| 회차 | 동시 커넥션 | 상태 확인 경로 처리량 |
|---|---|---|
| [#546](https://github.com/TicketRush/TicketRush-backend/issues/546) (VU 783) | 약 1,600 | **1,400 RPS** |
| #549 B-1 (VU 10,000) | **16,701** | **450 RPS** |

**대기 인원이 많을수록 커넥션이 늘어 `R`이 떨어지는데, 산식은 한산할 때 잰 `R`을 쓴다** — 정확히 붐빌 때 주기를 너무 짧게 지시한다. 대기열이 스스로를 무너뜨리는 구조다.

```
N=10,000 · R=1,400 → T=8초 → 폴링 수요 1,250 RPS
                     그 동시성에서의 실제 용량   450 RPS   (수요가 2.8배)
```

#549 B-1이 정확히 이 지점에서 무너졌다 — `queue_status_unavailable` 15.55%, 호스트 CPU 99.20%. `RedisCommandTimeoutException`이 25만 줄 찍혔지만 **Redis 컨테이너 CPU는 3.89%였고 `slowlog`는 비어 있었다.** Lettuce 타임아웃은 클라이언트 쪽에서 재는 값이라, 게이트웨이가 CPU를 못 잡으면 서버가 즉답해도 터진다.

**따라서 `R`은 보수적으로 잡는다 — `R = 400`.**

```
T = ceil(10,000 / 400) = 25초
```

이 값은 이 절이 원래 [#529](https://github.com/TicketRush/TicketRush-backend/issues/529)의 `seat-counts` 포화점 396.75 RPS를 빌려 쓴 값과 같다. **처음 추정이 옳았다.** #546이 그것을 "3.5배 보수적이었다"며 1,400으로 올린 것이 오히려 위험한 방향이었고, #546 자신이 남긴 경고 — *"`R = 1,400`은 '이 구성에서 도달한 값'이지 경로의 절대 상한이 아니다"* — 가 맞았다.

`R = 400`으로 되돌린 #549 B-2는 유효 회차가 됐다: `queue_status_unavailable` **0.00%**(0/147,807), 예매 서버 RPS **19.60-21.00**(유입 VU 2,317 → 10,000 동안), 호스트 CPU max 79.53%.

> **`R = 1,400`은 폐기하지 않고 조건부로 남긴다.** 동시 커넥션이 약 1,600 수준인 저부하 구간에서는 유효한 실측이다. 다만 **폴링 주기 산식의 입력으로는 쓸 수 없다** — 그 산식이 쓰이는 상황이 바로 고동시성이기 때문이다.
>
> **두 점으로 그은 선이다.** 함수 형태를 알려면 커넥션을 계단으로 올리는 별도 회차가 필요하다. 그 전까지는 보수적인 쪽을 택한다.

### 3.5 입장 허용량 `admit-rate-per-second` — 20에서 12로 (#555 실측, 2026-08-14)

**이 ADR을 쓸 당시 이 값에는 실측 근거가 없었다.** 설정 주석이 유일한 근거였고 그 주석은 [#344](https://github.com/TicketRush/TicketRush-backend/issues/344)의 "booking 단독 258 RPS"를 인용했는데, **그 258 RPS는 예매 처리량이 아니다** — 98.61%(94,359건)가 좌석 점유 반려 409였고 실제 예매 생성은 1,324건 / 6분 36초 = **약 3.3 RPS**였다. 폴링 용량 `R`은 200/400/800/1600 계단으로 무릎을 찾았지만 `admit`은 같은 작업을 한 번도 하지 않았다.

[#555](https://github.com/TicketRush/TicketRush-backend/issues/555)에서 계단 회차로 실측했다. 계단 하나 = 회차 하나이고(서버값이라 게이트웨이 `--force-recreate`가 필요하다), 도착률 `a = 2m` · 코호트 `C = 300m`으로 두어 어느 계단에서나 동일한 5분 판정창을 확보했다.

**무릎은 12와 16 사이다.** 판정 기준은 회차 **전에** 확정했다 — *"직전 계단 대비 admit 증가분의 50% 미만만 오르는 계단."*

| 구간 | admit 증가 | 예매 서버 RPS 증가 | 비율 | |
|---|---|---|---|---|
| 시리즈 B `12 → 16` | +4 | 8.22 → 9.84 = +1.62 | **40.5%** | 포화 |
| 시리즈 B `16 → 20` | +4 | 9.84 → 13.02 = +3.18 | 79.5% | 기준 위 |
| 시리즈 A `12 → 16` | +4 | 9.23 → 9.98 = +0.75 | **18.8%** | 포화 |

두 시리즈는 대상 설정이 달라 절대값으로 잇지 않는데도 **판정이 독립적으로 같은 구간을 가리켰다.** `admit-rate-per-second`를 **20 → 12**로 내린다. 12는 무릎의 정확한 값이 아니라 **통과가 확인된 가장 높은 계단**이다(좌석맵 성공률 99.49% · 폴링 p95 2.03s · 호스트 CPU max 61.89%).

#### 무릎을 만드는 것은 `admit`이 아니라 좌석 SSE 팬아웃이다

포화 축은 seat-service였고, 그 서비스의 HTTP는 **2.18 RPS**뿐이었다 — CPU를 먹는 것은 HTTP 경로가 아니다. 좌석맵 성공률이 두 시리즈에서 같은 지점에서 절벽처럼 떨어진다:

| admit | 시리즈 A | 시리즈 B |
|---|---|---|
| 12 | 100.00% | 99.49% |
| 16 | **63.06%** | **63.51%** |
| 20 | 38.14% | 73.26% |

**대조 회차가 인과를 확정했다.** `admit 16`에서 SSE 구독자만 100 → 0으로 두자 좌석맵 63.06% → **100.00%**, 호스트 CPU avg 64.61% → **33.65%**, 폴링 p95 2.73s → **180ms**로 돌아왔다. 구독자를 고정 100명으로 두면 팬아웃 sends/s = `100 × admit`이므로 **`admit`이 곧 seat-service의 부하**다.

따라서 **이 값을 낮추는 것은 증상 완화다.** 근본은 seat-service의 팬아웃 비용이고 별도 이슈로 남는다.

#### 이 실측의 한계

- **유입이 생성기 쪽 WAN 경로에서 잘렸다.** 계단마다 17~20%가 대기열 진입에 실패했고 k6의 실패 `status`가 전부 `0`(전송 계층)이었다. 대상의 `TcpAttemptFails`는 70건뿐인데 k6는 `connection refused`를 1,260건 봤고, 대상은 `TcpRetransSegs 263,142`를 기록했다. 예매 서버 RPS가 어느 계단에서나 8~13/s에 묶여 **처리량 축의 분해능이 낮다** — 결론이 좌석맵·CPU·소화 시간 축에 더 기댄다.
- **회차 도중 nginx/커널 백로그를 고쳤다.** `tcp_max_syn_backlog 512` · nginx `:443` 백로그 511(기본값)에서 SYN 큐가 15,826번 넘쳤다(`TcpExtTCPReqQFullDoCookies`). §5가 "nginx가 커넥션을 쥔다"고 적었지만 **`worker_connections`만으로는 부족했다** — 벽은 그보다 아래 계층이었다. 고친 뒤 시리즈 B 세 회차 모두 증가 0이다.
- 12와 16 사이는 재지 않았다. 상한(80·160)도 돌지 않았다 — 16에서 이미 포화했다.

증적: `load-tests/k6/results/260814-555-admit-rate-knee/` (판정 기준은 첫 계단 **이전**에 커밋했다).

### 4. 상태 확인 경로 초경량화

폴링 주기 다이얼은 그 경로가 가벼울 때만 의미가 있다. 무거운 것이 하나라도 끼면 주기를 아무리 늘려도 상한이 낮게 고정된다.

- **JWT 검증 체인을 태우지 않는다.** `JwtAuthenticationFilter`는 `GlobalFilter`라 모든 요청이 서명 검증을 지난다. 대기열 상태 확인은 대기 토큰(불투명 문자열) 대조로 끝낸다.
- **DB를 타지 않는다.** ZRANK 한 번이 전부다.
- **요청당 로그를 남기지 않는다.** 현재 `JwtAuthenticationFilter`는 요청마다 INFO 3줄을 찍는다(`🔥 JwtAuthenticationFilter 실행` 외). 1만 명 폴링에서 이것만으로 디스크와 CPU를 먹는다 — #403이 로그 폭주로 이미 한 번 겪은 실패 모드다. 대기열 경로는 이 필터를 우회하도록 라우팅한다.
- **응답을 작게 유지한다.** 순번·예상 대기·다음 폴링 시각만 담는다. `seat-counts` 응답이 203 bytes인 것이 참고 상한이다.

### 5. 대량 동시 연결 — 게이트웨이가 아니라 nginx가 커넥션을 쥔다

**이 절은 원래 "게이트웨이의 keep-alive 타임아웃을 짧게 둔다"였고, 그것은 틀렸다.** 게이트웨이 8080은 `127.0.0.1`로만 publish되고(`deploy/docker-compose.prod.yml`), 공개 진입점인 `deploy/nginx/api.ticketrush.store.conf`의 `location /`은 upstream 블록 없이 `proxy_pass` 직접이라 **`keepalive` 지시자가 구조적으로 부재**하다. nginx는 게이트웨이 커넥션을 재사용하지 않으므로 게이트웨이는 이미 요청마다 새 TCP를 받고 있고, **유휴 커넥션은 nginx가 쥔다.**

따라서 실제 상한은 nginx `worker_connections × worker_processes`이며, **프록시 요청 하나가 슬롯을 2개 먹는다**(클라이언트 1 + 업스트림 1).

[#546](https://github.com/TicketRush/TicketRush-backend/issues/546) 회차 전 실측에서 이 호스트는 `worker_connections 768`(Ubuntu 기본값) · 워커 2개 · 워커 fd soft 1,024였다 — **실질 동시 요청 약 768.** 1만 VU는커녕 회차 A(`maxVUs` 800)조차 앱이 아니라 nginx를 재게 되는 값이라 **16,384 / `worker_rlimit_nofile` 65,535로 상향했다**(호스트에만 있는 설정이라 [#522](https://github.com/TicketRush/TicketRush-backend/issues/522)에 기록).

**`keepalive_timeout`은 기본값 75초를 유지한다.** #546에서 75초와 15초를 A/B로 돌린 결과, 15초 단축은 호스트 CPU 이득이 없으면서(차이 +0.29%p) 게이트웨이 메모리(+15.9%)와 재핸드셰이크 비용(connecting p95 2.1배, TLS p95 3.4배)만 늘었다. 다만 그 회차는 VU 최대 783이라 **"1만 명이 유휴 커넥션을 물고 있는" 조건을 재현하지 못했다** — 1만 VU 회차에서 다시 본다.

## 검토한 대안

1. **SSE/WebSocket으로 순번을 밀어준다** — 기각. 1만 개의 장기 커넥션을 2 vCPU / 7.6 GiB가 감당하지 못한다. [#403](https://github.com/TicketRush/TicketRush-backend/issues/403)에서 SSE 구독자 600명만으로 seat-service 메모리가 계단마다 오르는 것을 봤고, [#509](https://github.com/TicketRush/TicketRush-backend/issues/509)의 OOM 원인 213 MiB가 스레드 153개로 설명됐다. 폴링은 "연결을 안 쥐는" 것이 유일한 장점이고, 이 인스턴스에서는 그 장점이 결정적이다.
2. **대기열 없이 Rate Limit만 둔다([#423](https://github.com/TicketRush/TicketRush-backend/issues/423))** — 기각(단, 병행한다). Rate Limit은 초과분을 **거절**한다. 티켓팅에서 거절은 "새로고침 폭탄"으로 되돌아와 부하를 오히려 키운다. 순번을 주면 기다린다 — 대기열이 푸는 것은 용량이 아니라 **사용자 행동**이다. 둘은 배타적이지 않고, 대기열이 못 막은 유입을 Rate Limit이 거절한다.
3. **대기열을 신규 서비스로 분리** — 기각. 메모리 예산에 자리가 없고(여유 518 MiB), 입장 토큰 검증에 홉이 하나 더 붙는다. MSA 순수성보다 이 인스턴스의 물리 제약이 앞선다.
4. **입장 토큰을 JWT로 발급** — 기각. 검증이 서명 연산이라 예매 경로마다 CPU를 쓴다. CPU가 이미 벽인 곳에서 그 비용을 새로 만들 이유가 없다. 불투명 토큰 + Redis 대조가 더 싸다.

## 결과

**얻는 것**

- 예매 경로의 부하가 유입 규모와 분리된다. 1만 명이 오든 10만 명이 오든 예매 API가 받는 것은 "입장 허용량"이다.
- 거절 대신 순번을 주므로 재시도 폭주가 사라진다.
- 폴링 주기가 서버 손에 있어, 대기 인원이 늘면 주기를 늘려 상태 확인 부하를 평평하게 유지할 수 있다.

**치르는 것**

- **게이트웨이의 장애 표면이 넓어진다.** reactive Redis 의존이 추가되고, 대기열 로직의 버그가 곧 전 경로의 장애다.
- **Redis SPOF 위에 기능이 하나 더 올라간다.** [ADR 0008](0008-accept-redis-spof-with-fail-closed.md)의 fail-closed 원칙상 Redis가 죽으면 대기열도 전면 차단이다. 대기열만 fail-open으로 두는 선택지는 **오픈 시각에 유입 제어가 통째로 사라진다**는 뜻이라 택하지 않는다.
- **폴링 주기만큼 순번 갱신이 늦다.** 25초 주기면 사용자는 최대 25초 묵은 순번을 본다. 티켓팅에서 이 정도 지연은 수용 가능하다고 판단하지만, 대기 인원이 적을 때는 주기를 줄여 체감을 개선한다.
- ~~**`R` 실측이 남는다.**~~ [#546](https://github.com/TicketRush/TicketRush-backend/issues/546)에서 확정했다(`R=1,400` / `T=8초`). 남은 것은 1만 VU 유입 제어 검증([#472](https://github.com/TicketRush/TicketRush-backend/issues/472) 완료 조건)이다.
- **`admit`을 낮추는 것은 증상 완화다.** [#555](https://github.com/TicketRush/TicketRush-backend/issues/555)가 무릎의 원인을 좌석 SSE 팬아웃으로 특정했다(§3.5). 구독자 고정 100명일 때 팬아웃 sends/s = `100 × admit`이라, `admit`을 올릴 여지는 seat-service의 팬아웃 비용을 먼저 낮춰야 생긴다. 그 전까지 `admit`은 두 축을 함께 조이는 다이얼이다.

**후속 작업**

- [#472](https://github.com/TicketRush/TicketRush-backend/issues/472) 대기열 구현 및 1만 VU 유입 제어 검증
- ~~상태 확인 경로 `R` 실측 → 폴링 주기 `T` 확정 → 이 ADR §3 갱신~~ — [#546](https://github.com/TicketRush/TicketRush-backend/issues/546)에서 완료(`R=1,400` / `T=8초`)
- 대기열 지표 추가(대기 인원, 입장 허용률, 상태 확인 RPS, 폴링 주기) — 관측 없이는 주기 다이얼을 돌릴 근거가 없다
- ~~입장 허용량 `admit-rate-per-second` 실측 → 이 ADR 갱신~~ — [#555](https://github.com/TicketRush/TicketRush-backend/issues/555)에서 완료(무릎 12~16, 값 12로 반영, §3.5)
- **좌석 SSE 팬아웃 비용 절감** — #555가 특정한 무릎의 원인이다. 이것을 낮추기 전에는 `admit`을 올릴 수 없다.
  - **병목은 실행기 큐가 아니다.** #555 전 회차에서 `executor_queued_tasks` 0 · 거부 0 · `caller_runs` 0이었다(#532의 역압 정책은 정상 동작했다). 비용은 **이벤트를 구독자 수만큼 직렬화해 써 내는 CPU 그 자체**다 — 같은 회차에서 seat-service의 HTTP는 2.18 RPS뿐인데 프로세스 CPU는 0.44(2 vCPU 중)였다.
  - **#555의 구독자 100명은 과대가 아니라 과소 가정이다.** 동시 구독자 ≈ `admit × 좌석 선택 화면 체류시간`이므로 `admit 12` + 체류 30초만 해도 360명이고, [#403](https://github.com/TicketRush/TicketRush-backend/issues/403)은 600명까지 검증했다. **실제 오픈은 측정보다 나쁘다.**
- ~~**nginx·커널 백로그 설정의 프로비저닝 자동화**~~ — 별도 후속으로 두지 않는다. `/etc/sysctl.d/99-ticketrush.conf`와 `/etc/nginx/sites-available/`는 디스크 파일이고 nginx는 `enabled`라 **재부팅에는 살아남는다**(2026-08-14 실측 확인). 유실되는 경우는 인스턴스를 새로 만들 때뿐인데 이 배포는 IaC가 아예 없어 **전체가 수동**이다. 백로그만 자동화 대상으로 떼어내는 것은 범위가 어긋난다 — 인프라 코드화를 하게 되면 그때 함께 들어갈 항목이고, 그때까지의 안전장치는 `deploy/sysctl/99-ticketrush.conf`의 적용 절차 주석이다.
