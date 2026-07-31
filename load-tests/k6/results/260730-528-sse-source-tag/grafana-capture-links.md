# Grafana Explore 캡처 링크 (#528)

이 회차의 증적 그래프를 찍기 위한 Grafana Explore 링크다. 쿼리와 측정 창이 URL 에 박혀 있으므로 손으로 맞출 필요가 없다.

## 캡처 절차

Grafana 에 이미지 렌더러 플러그인이 없어 **PNG 를 서버가 만들어 주지 못한다.** 아래 링크를 열어
화면을 직접 캡처해 같은 디렉토리에 지정된 파일명으로 저장한다(#403·#504 회차와 같은 관행).

1. **SSH 터널을 올린다.** Grafana(3000)·Prometheus(9090)는 `127.0.0.1` 바인딩이라
   인터넷에서 접근할 수 없다(ADR 0007, 런북 §7.1).
   ```bash
   ssh -i <key>.pem -L 3000:localhost:3000 -L 9090:localhost:9090 ubuntu@<EC2_탄력적_IP>
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

> Prometheus 보존은 15일이다. **2026-08-14 이후에는 이 링크들이 빈 그래프가 된다.** 그때는 같은
> 디렉토리의 `timeseries-*.json` 덤프가 유일한 원자료다(그래서 함께 커밋한다).

> 그래프의 시각은 브라우저 시간대(KST)로 보인다. 이 문서와 리포트의 시각은 전부 **UTC** 다 —
> 9시간을 빼고 읽는다(`19:32` = `10:32Z`).

### 이 회차의 캡처 함정

- **`k6_vus`(0-600)를 전파 지연(약 5,200ms)과 같은 축에 넣지 않는다.** #403 이 그렇게 찍었다가
  두 선의 자릿수가 3자리 달라 그래프를 다시 찍어야 했다. 구독자 수는 시간축으로 읽는다 —
  계단이 10분씩이라 창만 보면 어느 구간인지 안다.
- **큐 깊이(0-997)와 발행률(0-44/s)을 분리했다.** 한 축에 두면 발행률 선이 바닥에 눌리는데,
  이 회차의 본론이 바로 그 선이다. 그래서 `graph-burst-attribution.png` 를 따로 둔다 —
  **두 그래프의 시간 창이 같으므로 나란히 놓고 읽으면 된다.**
- **`scheduler_fallback` 은 정상 상태에서 0.85/s 라 거의 안 보인다.** 그게 정상이고, 버스트 창에서
  44.4/s 로 튀는 것이 대비다. 두 창의 세로 눈금을 억지로 맞추지 말 것.

## 캡처 목록

**5장이다.**

### graph-burst-attribution.png — **이 회차의 본 그림.** 경로 5종의 발행률. 주입(10:31:59Z) 직후 `scheduler_fallback` 만 25 → 44.4/s 로 튀고 나머지는 평평하다 — §3.2 의 근거

측정 창: 2026-07-30T10:30:30Z ~ 2026-07-30T10:36:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%20by%20%28source%29%20%28rate%28ticketrush_seat_sse_event_published_total%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785407430000%22%2C%22to%22%3A%221785407760000%22%7D%7D%7D

### graph-burst-queue.png — 같은 창의 큐 깊이 · 활성 스레드 · 풀 크기. 위 그래프와 시간축이 같으니 겹쳐 읽는다. 큐가 997 에 닿은 **뒤에야** 풀이 4 → 16 으로 는다

측정 창: 2026-07-30T10:30:30Z ~ 2026-07-30T10:36:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22executor_queued_tasks%7Bname%3D%5C%22seatStatusSseExecutor%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22executor_active_threads%7Bname%3D%5C%22seatStatusSseExecutor%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22executor_pool_size_threads%7Bname%3D%5C%22seatStatusSseExecutor%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785407430000%22%2C%22to%22%3A%221785407760000%22%7D%7D%7D

### graph-sse-steady.png — 정상 상태(구독자 100·300·600). **큐가 바닥에 붙어 있다** — #403 의 평균 50 · 최대 307 과 정반대. `expire_single` 4.18 vs `scheduler_fallback` 0.85 의 83:17 도 여기서 보인다

측정 창: 2026-07-30T09:46:00Z ~ 2026-07-30T10:18:30Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%20by%20%28source%29%20%28rate%28ticketrush_seat_sse_event_published_total%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22executor_queued_tasks%7Bname%3D%5C%22seatStatusSseExecutor%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785404760000%22%2C%22to%22%3A%221785406710000%22%7D%7D%7D

### graph-sse-propagation.png — 전파 지연 p95 · p99 (ms). **구독자를 6배 올려도 평평하다** — §4 의 근거. ⚠ `k6_vus` 를 같은 축에 넣지 않는다(#403 이 그렇게 했다가 재촬영했다)

측정 창: 2026-07-30T09:46:00Z ~ 2026-07-30T10:18:30Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%221000%20%2A%20k6_sse_propagation_ms_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20k6_sse_propagation_ms_p99%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785404760000%22%2C%22to%22%3A%221785406710000%22%7D%7D%7D

### graph-sse-resources.png — 호스트 CPU % 와 seat-service 메모리 사용률 %. **둘 다 백분율이라 한 축에 둬도 된다.** 컨테이너 메모리는 #515 로 이번에 처음 생긴 축이다

측정 창: 2026-07-30T09:46:00Z ~ 2026-07-30T10:18:30Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20%281%20-%20avg%28rate%28node_cpu_seconds_total%7Bjob%3D%5C%22node%5C%22%2C%20mode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D%5C%22seat-service%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D%5C%22seat-service%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785404760000%22%2C%22to%22%3A%221785406710000%22%7D%7D%7D

