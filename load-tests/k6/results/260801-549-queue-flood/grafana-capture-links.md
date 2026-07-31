# Grafana Explore 캡처 링크 (#549 회차 B)

이 회차의 증적 그래프를 찍기 위한 Grafana Explore 링크다. 쿼리와 측정 창이 URL 에 박혀 있으므로 손으로 맞출 필요가 없다.

## 캡처 절차

Grafana 에 이미지 렌더러 플러그인이 없어 **PNG 를 서버가 만들어 주지 못한다.** 아래 링크를 열어
화면을 직접 캡처해 같은 디렉토리에 지정된 파일명으로 저장한다(#403·#504·#529 회차와 같은 관행).

1. **SSH 터널을 올린다.** Grafana(3000)·Prometheus(9090)는 `127.0.0.1` 바인딩이라
   인터넷에서 접근할 수 없다(ADR 0007, 런북 §7.1).
   ```bash
   ssh -i <key>.pem -L 3000:localhost:3000 -L 9090:localhost:9090 ubuntu@54.116.243.250
   ```
2. **Grafana 에 먼저 로그인한다.** http://localhost:3000
   로그인하지 않은 채 Explore 링크를 열면 로그인 화면으로 튕기고, 로그인 후에는
   쿼리·시간 범위가 유실된다.
3. **아래 링크를 그대로 연다.** datasource·쿼리·시간 범위(UTC 절대 시각)가 모두 박혀 있다.
4. **브라우저 캡처로 저장한다.** 파일명은 각 절의 제목에 적힌 이름 그대로, 저장 위치는
   이 파일과 같은 디렉토리다.
5. 다 찍으면 PR 본문의 `📸 스크린샷` 절을 갱신한다.

> ⚠️ **부하 측정 중에는 Grafana 를 열지 않는다.** 패널마다 주기적으로 쿼리가 나가 측정 대상의
> CPU 를 소모한다(ADR 0004). 데이터는 TSDB 에 남으므로 회차가 끝난 뒤에 열어 본다.

> Prometheus 보존은 15일이다. **2026-08-15 이후에는 이 링크들이 빈 그래프가 된다.** 그때는 같은
> 디렉토리의 `timeseries-*.json` 덤프가 유일한 원자료다(그래서 함께 커밋한다).

> 그래프의 시각은 브라우저 시간대(KST)로 보인다. 이 문서와 리포트의 시각은 전부 **UTC** 다 —
> 9시간을 빼고 읽는다(`01:01` = `16:01Z`).

### 이 회차의 캡처 함정

- **회차가 둘이다.** B-1(무효, 15:50:15Z-15:54:26Z)과 B-2(유효, 16:01:25Z-16:22:41Z). 비교
  캡처는 두 회차를 한 창(15:48-16:25)에 담아 **가운데 골짜기를 기준으로 좌우를 나눠 읽는다.**
- **15초 샘플러의 중앙값을 '최대' 로 쓰지 않는다.** 묶음 C 에서 두 번 틀렸다(#469 Redis
  6.76 → 7.46%, #540 seat RSS 586 → 602 MiB). 최대값은 `timeseries-*.json` 에서 뽑는다.
- **`RedisCommandTimeoutException` 을 Redis 장애로 읽지 않는다.** B-1 에서 25만 줄이 찍혔지만
  Redis 컨테이너 CPU 는 3.89% 였고 `slowlog` 는 비어 있었다. Lettuce 타임아웃은 클라이언트
  쪽에서 재는 값이라, 게이트웨이가 CPU 를 못 잡으면 서버가 즉답해도 터진다.
- **게이트웨이 `http_server_requests` 의 `uri` 라벨은 못 쓴다.** `/**`·`UNKNOWN` 으로 뭉개진다
  (#402 실측 카디널리티 4). 경로별 구분은 `ticketrush_queue_*` 커스텀 지표로만 가능하다.

## 캡처 목록

**8장이다.**

### graph-booking-rps-vs-vus-b2.png — **이 회차의 전부.** 유입(VU)이 0 → 10,000 으로 오르는데 예매 RPS 는 19.60-21.00 에 평평하다

측정 창: 2026-07-31T16:01:25Z ~ 2026-07-31T16:22:41Z (UTC)

> 두 축의 스케일이 관건이다. 예매 RPS 는 0-30, VU 는 0-10,000 이라 한 축에 그리면 예매 선이 바닥에 붙어 '평평함' 이 안 보인다. **좌측 축(A)을 0-40 으로 고정**하고 VU 는 우측 축으로 뺀다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22booking-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22k6_vus%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785513685000%22%2C%22to%22%3A%221785514961000%22%7D%7D%7D

### graph-queue-waiting-b2.png — 대기 인원 단조 감소(max 3,254 → 0)와 서버가 지시한 T(max 9초)가 그것을 따라가는 모습

측정 창: 2026-07-31T16:01:25Z ~ 2026-07-31T16:22:41Z (UTC)

> **대기 인원 최대가 3,254 명이다.** 1만으로 오독하기 쉽다 — '1만 명이 동시에 줄 서 있는' 조건은 재현되지 않았고(리포트 §9), 이 그래프가 그 증거다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_queue_waiting%7Bjob%3D%5C%22gateway%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22ticketrush_queue_poll_interval_seconds%7Bjob%3D%5C%22gateway%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785513685000%22%2C%22to%22%3A%221785514961000%22%7D%7D%7D

### graph-queue-admission-b2.png — 승급률과 **`unavailable` 이 0 인 것**. admitted max 20.67, waiting max 365.89, unavailable 0.00

측정 창: 2026-07-31T16:01:25Z ~ 2026-07-31T16:22:41Z (UTC)

> 범례에 `unavailable` 선이 **보이긴 하되 0 에 붙어 있어야** 한다. 아예 안 보이면 시리즈가 누락된 것인지 값이 0 인지 구분되지 않으므로, 범례 목록에서 이름을 확인한다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%20by%20%28result%29%20%28rate%28ticketrush_queue_admission_total%7Bjob%3D%5C%22gateway%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785513685000%22%2C%22to%22%3A%221785514961000%22%7D%7D%7D

### graph-unavailable-compare.png — **B-1 무효의 원인축.** `unavailable` 이 동시 커넥션에 붙어 있다 — 커넥션이 4,000 아래로 떨어지자 0 이 됐다. 앞 봉우리가 B-1(커넥션 16,701), 뒤가 B-2(4,752)다

측정 창: 2026-07-31T15:48:00Z ~ 2026-07-31T16:25:00Z (UTC)

> 두 회차가 한 화면에 나란히 나온다 — **설정 하나(R=1400→400) 바꿔 이렇게 달라졌다**가 이 한 장에 담긴다. 커넥션(B)은 우측 축으로 뺀다(0-17,000 대 0-70).

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28ticketrush_queue_admission_total%7Bjob%3D%5C%22gateway%5C%22%2C%20result%3D%5C%22unavailable%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22node_netstat_Tcp_CurrEstab%7Bjob%3D%5C%22node%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785512880000%22%2C%22to%22%3A%221785515100000%22%7D%7D%7D

### graph-host-cpu-compare.png — B-1 max **99.20%**(avg 68.01) vs B-2 max **79.53%**(avg 35.27)

측정 창: 2026-07-31T15:48:00Z ~ 2026-07-31T16:25:00Z (UTC)

> 가운데 골짜기가 두 회차 사이의 정리·재기동 구간이다. 그 골을 기준으로 좌우를 나눠 읽는다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20%281%20-%20avg%28rate%28node_cpu_seconds_total%7Bjob%3D%5C%22node%5C%22%2C%20mode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785512880000%22%2C%22to%22%3A%221785515100000%22%7D%7D%7D

### graph-status-latency-compare.png — 상태 확인 p95 — B-1 **5.27s** vs B-2 **32.1ms**

측정 창: 2026-07-31T15:48:00Z ~ 2026-07-31T16:25:00Z (UTC)

> **로그 축으로 두어야 한다.** 5.27s 와 32ms 는 164배 차이라 선형 축이면 B-2 가 바닥에 뭉개져 '0 이었다' 처럼 보인다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22k6_queue_status_duration_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22k6_queue_wait_to_admit_seconds_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785512880000%22%2C%22to%22%3A%221785515100000%22%7D%7D%7D

### graph-redis-memory-b2.png — 무효 기준 감시축. max **13.86%** (기준 75% = 48MB/64MB)

측정 창: 2026-07-31T16:01:25Z ~ 2026-07-31T16:22:41Z (UTC)

> `noeviction` 이라 상한에 닿으면 대기열이 아니라 **좌석 락 SET 이 거절된다**(ADR 0008). 이 회차는 여유가 5배 이상이었다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20redis_memory_used_bytes%20%2F%20redis_memory_max_bytes%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785513685000%22%2C%22to%22%3A%221785514961000%22%7D%7D%7D

### graph-gateway-memory-b2.png — `mem_limit` 512m(536,870,912 B) 대비 여유. reactive Lettuce/netty 가 올라간 뒤 1만 VU 첫 부하다

측정 창: 2026-07-31T16:01:25Z ~ 2026-07-31T16:22:41Z (UTC)

> 바이트 단위라 눈금이 크다. 512m 선(536,870,912)을 머릿속에 두고 읽거나 패널 단위를 bytes 로 바꿔 찍는다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_container_memory_usage_bytes%7Bname%3D%5C%22gateway-service%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785513685000%22%2C%22to%22%3A%221785514961000%22%7D%7D%7D

