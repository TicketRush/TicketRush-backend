# Grafana Explore 캡처 링크 (#403)

이 회차(#403)의 증적 그래프 7장을 찍기 위한 Grafana Explore 링크다. 쿼리와 측정 창이 URL 에 박혀 있으므로 손으로 맞출 필요가 없다.

## 캡처 절차

Grafana 에 이미지 렌더러 플러그인이 없어 **PNG 를 서버가 만들어 주지 못한다.** 아래 링크를 열어
화면을 직접 캡처해 같은 디렉토리에 지정된 파일명으로 저장한다(#346·#347 회차와 같은 관행).

1. **SSH 터널을 올린다.** Grafana(3000)·Prometheus(9090)는 `127.0.0.1` 바인딩이라
   인터넷에서 접근할 수 없다(ADR 0007, 런북 §7.1).
   ```bash
   ssh -i <key>.pem -L 3000:localhost:3000 -L 9090:localhost:9090 ubuntu@<EC2_탄력적_IP>
   ```
2. **Grafana 에 로그인한다.** http://localhost:3000 (기본 `admin`/`admin`).
   로그인하지 않은 채 Explore 링크를 열면 로그인 화면으로 튕기고, 로그인 후에는
   쿼리·시간 범위가 유실되므로 **로그인을 먼저 끝내 둔다.**
3. **아래 링크를 그대로 연다.** 각 링크에 datasource·쿼리·시간 범위(UTC 절대 시각)가
   모두 박혀 있다. 손으로 범위를 맞추지 않는다 — 회차 창이 어긋나면 그래프가 다른 것을 말한다.
4. 그래프가 그려지면 **범례가 잘리지 않는지** 보고, 필요하면 패널 높이를 늘린다.
   쿼리가 여러 개인 그래프는 축 단위가 섞일 수 있다(ms 와 개수 등) — 그 경우
   `Ctrl` 없이 각 쿼리의 눈 아이콘으로 하나씩 껐다 켜며 2장으로 나눠 찍어도 된다.
5. **브라우저 캡처로 저장한다.** 파일명은 아래 각 절의 제목에 적힌 이름 그대로,
   저장 위치는 이 파일과 같은 디렉토리다.
6. 다 찍으면 `report.md` §9 의 완료조건 6 줄을 ⚠ 에서 ✅ 로 바꾼다.

> ⚠️ **부하 측정 중에는 Grafana 를 열지 않는다.** 패널마다 주기적으로 쿼리가 나가 측정 대상의
> CPU 를 소모한다(런북 §7.2). 데이터는 TSDB 에 남으므로 회차가 끝난 뒤에 열어 본다.

> 시간 범위가 비어 보이면 Prometheus 보존 기간을 확인한다. 회차 창이 보존 기간 밖으로
> 밀려나면 이 링크들은 빈 그래프가 되고, 그때는 같은 디렉토리의 `timeseries-*.json`
> 덤프가 유일한 원자료다(그래서 덤프를 함께 커밋한다).


## 캡처 목록

### graph-counts-a-latency.png — 스케일 A(600석) — 계단별 서버 지연. 완료조건 2 의 좌석수 대비 곡선 재료

측정 창: 2026-07-28T08:03:09Z ~ 2026-07-28T08:33:59Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%221000+%2A+histogram_quantile%280.95%2C+sum+by+%28le%29+%28rate%28http_server_requests_seconds_bucket%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000+%2A+sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29+%2F+sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785225789000%22%2C%22to%22%3A%221785227639000%22%7D%7D%7D

### graph-counts-b-latency.png — 스케일 B(3,000석) — 같은 계단·같은 축. A 와 나란히 놓으면 좌석 수의 몫이 보인다

측정 창: 2026-07-29T04:14:28Z ~ 2026-07-29T04:45:18Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%221000+%2A+histogram_quantile%280.95%2C+sum+by+%28le%29+%28rate%28http_server_requests_seconds_bucket%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000+%2A+sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29+%2F+sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785298468000%22%2C%22to%22%3A%221785300318000%22%7D%7D%7D

### graph-counts-b-saturation.png — 스케일 B 포화 축 — 도착률을 1.5배 올려도 처리량이 멈추는 지점. 완료조건 2 의 포화점

측정 창: 2026-07-29T04:14:28Z ~ 2026-07-29T04:45:18Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100+%2A+%281+-+avg%28rate%28node_cpu_seconds_total%7Bjob%3D%5C%22node%5C%22%2C+mode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22hikaricp_connections_pending%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22D%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785298468000%22%2C%22to%22%3A%221785300318000%22%7D%7D%7D

### graph-sse-propagation.png — 구독자 수 대비 전파 지연 (ms) — 완료조건 3. 계단별 p95 는 1,249 / 2,158 / 2,768ms

측정 창: 2026-07-29T04:56:30Z ~ 2026-07-29T05:29:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%221000+%2A+k6_sse_propagation_ms_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000+%2A+k6_sse_propagation_ms_p99%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%221000+%2A+k6_sse_probe_booking_duration_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785300990000%22%2C%22to%22%3A%221785302940000%22%7D%7D%7D

### graph-sse-subscribers.png — 동시 구독자 계단 — VU 1개 = SSE 커넥션 1개. 위 지연 그래프와 같은 창이라 겹쳐 읽는다

측정 창: 2026-07-29T04:56:30Z ~ 2026-07-29T05:29:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22k6_vus%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785300990000%22%2C%22to%22%3A%221785302940000%22%7D%7D%7D

### graph-sse-executor.png — SSE 전송 스레드풀 — 큐 깊이·활성 스레드·풀 크기. 완료조건 4 의 지표 증적

측정 창: 2026-07-29T04:56:30Z ~ 2026-07-29T05:29:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22executor_queued_tasks%7Bname%3D%5C%22seatStatusSseExecutor%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22executor_active_threads%7Bname%3D%5C%22seatStatusSseExecutor%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22executor_pool_size_threads%7Bname%3D%5C%22seatStatusSseExecutor%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22D%22%2C%22expr%22%3A%22rate%28executor_completed_tasks_total%7Bname%3D%5C%22seatStatusSseExecutor%5C%22%7D%5B1m%5D%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785300990000%22%2C%22to%22%3A%221785302940000%22%7D%7D%7D

### graph-sse-resources.png — SSE 회차 자원 축 — 600 커넥션을 붙든 채의 CPU·힙·스레드

측정 창: 2026-07-29T04:56:30Z ~ 2026-07-29T05:29:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100+%2A+%281+-+avg%28rate%28node_cpu_seconds_total%7Bjob%3D%5C%22node%5C%22%2C+mode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22jvm_memory_used_bytes%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+area%3D%5C%22heap%5C%22%7D+%2F+1048576%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22jvm_threads_live_threads%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22D%22%2C%22expr%22%3A%22tomcat_threads_current_threads%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785300990000%22%2C%22to%22%3A%221785302940000%22%7D%7D%7D

### graph-sse-receipt.png — 수신 축 — 구독자 전체 수신율과 커넥션 종료. 누락은 지연이 아니라 유실로 나타난다

측정 창: 2026-07-29T04:56:30Z ~ 2026-07-29T05:29:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22rate%28k6_sse_events_received_total%5B1m%5D%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22rate%28k6_sse_connected_total%5B1m%5D%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22rate%28k6_sse_connection_closed_total%5B1m%5D%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22D%22%2C%22expr%22%3A%22k6_sse_mutate_created_rate%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785300990000%22%2C%22to%22%3A%221785302940000%22%7D%7D%7D

