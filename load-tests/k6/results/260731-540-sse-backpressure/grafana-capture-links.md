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

> ⚠️ **실제로 찍어 보니 이 지시가 부족했다(2026-07-31).** `graph-queue-vs-pool.png` 에서 큐가
> 986 까지 치솟는 것은 잘 보이지만, **pool(4→16)과 active 선이 큐 스케일에 눌려 바닥에 붙는다.**
> "pool 은 큐가 찬 *뒤에* 늘었다"는 이 장의 핵심 논지가 그림으로는 읽히지 않는다.
> 수치는 `report.md` §4 표와 `metadata.txt` 에 있으므로 논지 자체는 서지만, 다음 회차에서
> 이 축을 다시 찍는다면 **pool·active 만 따로 한 장**을 더 만드는 편이 낫다
> (아래 "추가 후보" 참조).

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


## 보완 캡처 (찍었다)

### graph-pool-growth.png — pool·active 만 떼어 4 → 16 확장 시점을 보이는 장

위 `graph-queue-vs-pool.png` 가 스케일 때문에 못 보여주는 것을 보완한다. 큐 선을 빼면
세로 눈금이 0-16 으로 잡혀 확장 시점이 드러난다. **큐 그래프와 가로 시간축이 같으므로
두 장을 나란히 놓으면 "큐가 먼저, pool 이 나중" 순서가 보인다.**

> ✅ **찍었다.** pool 이 4 에서 평평하다 **07:57:00Z 에 16 으로 점프**하고, 그 시각이
> `graph-queue-vs-pool.png` 에서 큐가 치솟기 시작한 시점과 일치한다. 논지가 그림으로 섰다.
> 부수 관측 둘 — pool 은 16 으로 늘어난 뒤 회차 끝까지 줄지 않았고, 큐 해소 후에도
> active 가 07:03·07:04 에 12·16 으로 튄다(정상 부하에서도 팬아웃이 순간적으로 몰린다).

측정 창: 2026-07-31T07:53:30Z ~ 2026-07-31T08:05:30Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22executor_pool_size_threads%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2Cname%3D%5C%22seatStatusSseExecutor%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22executor_active_threads%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2Cname%3D%5C%22seatStatusSseExecutor%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785484410000%22%2C%22to%22%3A%221785485130000%22%7D%7D%7D
