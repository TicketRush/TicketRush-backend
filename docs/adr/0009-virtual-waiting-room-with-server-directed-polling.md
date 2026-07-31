# 9. 대기열을 게이트웨이 안의 모듈로 두고, 서버가 지시하는 폴링으로 1만 VU 유입을 제어한다

날짜: 2026-07-31

## 상태

승인됨

폴링 주기 `T`와 keep-alive 방향은 [#546](https://github.com/TicketRush/TicketRush-backend/issues/546) 실측으로 확정했다(2026-07-31). §3·§5가 그 수치로 갱신됐다.

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

**`R = 약 1,400 RPS` — [#546](https://github.com/TicketRush/TicketRush-backend/issues/546)에서 실측했다**(2026-07-31, `load-tests/k6/results/260731-546-queue-status/`).

```
T = ceil(10,000 / 1,400) = 8초
```

계단 `200 → 400 → 800 → 1600 RPS × 5분`으로 올렸고, 무릎은 1600 계단이었다 — 실제 RPS가 목표의 87.5%로 꺾이고(200~800은 94~96% 소화) `dropped_iterations`가 95건에서 39,634건으로 급증했으며 호스트 CPU가 90.7%에 닿았다. 세 신호가 같은 계단을 가리켰다.

**당초 하한 추정이 3.5배 보수적이었다.** 이 절은 원래 [#529](https://github.com/TicketRush/TicketRush-backend/issues/529)의 `seat-counts` 포화점 396.75 RPS를 빌려 `R ≥ 400 / T ≥ 25초`로 시작했다. "DB를 타지 않는 ZRANK 경로는 그보다 빠를 수밖에 없다"는 근거는 방향이 맞았고 폭이 컸다 — 폴링 1회가 실제로 Redis 명령 2회(`GET` + `ZRANK`)뿐임을 [PR #544](https://github.com/TicketRush/TicketRush-backend/pull/544)의 통합 테스트가 `INFO commandstats`로 고정하고 있다.

> **`R = 1,400`은 "이 구성에서 도달한 값"이지 경로의 절대 상한이 아니다.** 1600 계단에서 k6 VU가 `maxVUs` 800에 닿아 `dropped`의 일부가 생성기 측 제약일 수 있다. 더 위를 보려면 계단 연장 회차가 필요하다. 또한 이 회차는 nginx `worker_connections`를 768 → 16,384로 올린 뒤의 값이라(아래 §5) 그 전 회차들과 절대값을 직접 잇지 않는다.

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

**후속 작업**

- [#472](https://github.com/TicketRush/TicketRush-backend/issues/472) 대기열 구현 및 1만 VU 유입 제어 검증
- ~~상태 확인 경로 `R` 실측 → 폴링 주기 `T` 확정 → 이 ADR §3 갱신~~ — [#546](https://github.com/TicketRush/TicketRush-backend/issues/546)에서 완료(`R=1,400` / `T=8초`)
- 대기열 지표 추가(대기 인원, 입장 허용률, 상태 확인 RPS, 폴링 주기) — 관측 없이는 주기 다이얼을 돌릴 근거가 없다
