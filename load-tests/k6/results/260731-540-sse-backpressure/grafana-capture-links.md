# Grafana Explore 캡처 링크 (#540 — SSE 큐 역압 재현)

쿼리와 측정 창이 URL 에 박혀 있다. 절차는 `#539` 회차의 같은 문서와 동일하다
(SSH 터널 3000·9090 → **Grafana 로그인 먼저** → 링크 → 브라우저 캡처 → 이 디렉토리에 저장).

> ⚠️ 부하 측정 중에는 Grafana 를 열지 않는다(ADR 0004). 회차가 끝난 뒤에 연다.
> Prometheus 보존 15일 — **2026-08-15 이후에는 빈 그래프가 된다.** 그때는 같은 디렉토리의
> `timeseries-*.json` 이 유일한 원자료다.
> 그래프 시각은 브라우저 시간대(KST)로 보인다. 이 문서는 전부 **UTC** 다(`16:57` = `07:57Z`).

### 이 회차의 캡처 함정

- **거부 0 을 "데이터 없음"과 구분해서 보여야 한다.** 카운터가 생성자에서 등록돼 있어
  시계열은 존재하고 값이 0 으로 평평하다. 범례에 `rejected_total` 이 **보이는 채로** 0 인 것을
  찍어야 증적이 된다 — 범례가 비면 "수집이 안 된 것"과 구분되지 않는다.
- **큐와 pool 을 한 그래프에 넣는다.** 두 선의 **순서**(큐가 먼저 차고 pool 이 나중에 는다)가
  이 회차의 논지라, 따로 찍으면 그 순서가 안 보인다.
- **주입 시각 07:56:37Z 를 기준선으로 읽는다.** 그 직후 tick(07:56:48Z)이 2,000건을 밀어 넣는다.
- 세로 눈금을 손으로 맞추지 말 것 — 큐(0-1000)와 pool(4-16)은 자릿수가 달라 자동 스케일 그대로 찍고
  눈금 값을 읽는다.

## 캡처 목록

**4장이다.** 측정 창은 모두 2026-07-31T07:53:30Z ~ 2026-07-31T08:05:30Z (UTC).

### graph-queue-vs-pool.png — **이 회차의 핵심 그림.** 큐가 986까지 차오른 뒤에야 pool 이 4 → 16 으로 늘어난다. 순서가 뒤집혀 보이면 오독이다 — ThreadPoolTaskExecutor 는 큐가 다 찬 뒤에만 스레드를 늘린다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22executor_queued_tasks%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2Cname%3D%5C%22seatStatusSseExecutor%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22executor_pool_size_threads%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2Cname%3D%5C%22seatStatusSseExecutor%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22executor_active_threads%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2Cname%3D%5C%22seatStatusSseExecutor%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785484410000%22%2C%22to%22%3A%221785485130000%22%7D%7D%7D

### graph-rejected-vs-callerruns.png — **거부 0 · 역압 37.** 거부 선이 바닥에 평평하게 붙어 있는 것이 통과 증적이다(no data 가 아니라 0 이다 — 카운터가 생성자에서 등록돼 있다). #528 은 같은 조건에서 649건이 올랐다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_seat_sse_event_rejected_total%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22ticketrush_seat_sse_event_caller_runs_total%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785484410000%22%2C%22to%22%3A%221785485130000%22%7D%7D%7D

### graph-published-by-source.png — **발행 경로별 도착률(#520 태그).** scheduler_fallback 이 33.86/s 로 치솟는 구간이 주입분이고, booking_hold 5.53 · expire_single 5.40 은 정상 부하분이다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%20by%20%28source%29%20%28rate%28ticketrush_seat_sse_event_published_total%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785484410000%22%2C%22to%22%3A%221785485130000%22%7D%7D%7D

### graph-seat-rss.png — **구독자 600 + 큐 포화 구간의 seat-service 메모리.** 580-586 MiB / 640 MiB(약 91%)로 안정. OOM·재시작 없음

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_container_memory_usage_bytes%7Bcontainer%3D%5C%22seat-service%5C%22%7D%2F1024%2F1024%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D%5C%22seat-service%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D%5C%22seat-service%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785484410000%22%2C%22to%22%3A%221785485130000%22%7D%7D%7D

