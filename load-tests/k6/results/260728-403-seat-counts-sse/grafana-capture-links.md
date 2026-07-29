# Grafana Explore 캡처 링크 (#403)

SSH 터널(-L 3000:localhost:3000)이 떠 있어야 한다.

## graph-counts-a-latency.png — 스케일 A(600석) — 계단별 서버 지연. 완료조건 2 의 좌석수 대비 곡선 재료

측정 창: 2026-07-28T08:03:09Z ~ 2026-07-28T08:33:59Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%221000+%2A+histogram_quantile%280.95%2C+sum+by+%28le%29+%28rate%28http_server_requests_seconds_bucket%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000+%2A+sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29+%2F+sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785225789000%22%2C%22to%22%3A%221785227639000%22%7D%7D%7D

## graph-counts-b-latency.png — 스케일 B(3,000석) — 같은 계단·같은 축. A 와 나란히 놓으면 좌석 수의 몫이 보인다

측정 창: 2026-07-29T04:14:28Z ~ 2026-07-29T04:45:18Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%221000+%2A+histogram_quantile%280.95%2C+sum+by+%28le%29+%28rate%28http_server_requests_seconds_bucket%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000+%2A+sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29+%2F+sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785298468000%22%2C%22to%22%3A%221785300318000%22%7D%7D%7D

## graph-counts-b-saturation.png — 스케일 B 포화 축 — 도착률을 1.5배 올려도 처리량이 멈추는 지점. 완료조건 2 의 포화점

측정 창: 2026-07-29T04:14:28Z ~ 2026-07-29T04:45:18Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100+%2A+%281+-+avg%28rate%28node_cpu_seconds_total%7Bjob%3D%5C%22node%5C%22%2C+mode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22hikaricp_connections_pending%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22D%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785298468000%22%2C%22to%22%3A%221785300318000%22%7D%7D%7D

## graph-sse-propagation.png — 구독자 수 대비 전파 지연 — 완료조건 3. k6_vus 계단이 곧 동시 구독자 수다

측정 창: 2026-07-29T04:56:30Z ~ 2026-07-29T05:29:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22k6_sse_propagation_ms_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22k6_sse_propagation_ms_p99%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22k6_sse_probe_booking_duration_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22D%22%2C%22expr%22%3A%22k6_vus%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785300990000%22%2C%22to%22%3A%221785302940000%22%7D%7D%7D

## graph-sse-executor.png — SSE 전송 스레드풀 — 큐 깊이·활성 스레드·풀 크기. 완료조건 4 의 지표 증적

측정 창: 2026-07-29T04:56:30Z ~ 2026-07-29T05:29:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22executor_queued_tasks%7Bname%3D%5C%22seatStatusSseExecutor%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22executor_active_threads%7Bname%3D%5C%22seatStatusSseExecutor%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22executor_pool_size_threads%7Bname%3D%5C%22seatStatusSseExecutor%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22D%22%2C%22expr%22%3A%22rate%28executor_completed_tasks_total%7Bname%3D%5C%22seatStatusSseExecutor%5C%22%7D%5B1m%5D%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785300990000%22%2C%22to%22%3A%221785302940000%22%7D%7D%7D

## graph-sse-resources.png — SSE 회차 자원 축 — 600 커넥션을 붙든 채의 CPU·힙·스레드

측정 창: 2026-07-29T04:56:30Z ~ 2026-07-29T05:29:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100+%2A+%281+-+avg%28rate%28node_cpu_seconds_total%7Bjob%3D%5C%22node%5C%22%2C+mode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22jvm_memory_used_bytes%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C+area%3D%5C%22heap%5C%22%7D+%2F+1048576%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22jvm_threads_live_threads%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22D%22%2C%22expr%22%3A%22tomcat_threads_current_threads%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785300990000%22%2C%22to%22%3A%221785302940000%22%7D%7D%7D

## graph-sse-receipt.png — 수신 축 — 구독자 전체 수신율과 커넥션 종료. 누락은 지연이 아니라 유실로 나타난다

측정 창: 2026-07-29T04:56:30Z ~ 2026-07-29T05:29:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22rate%28k6_sse_events_received_total%5B1m%5D%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22rate%28k6_sse_connected_total%5B1m%5D%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22rate%28k6_sse_connection_closed_total%5B1m%5D%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22D%22%2C%22expr%22%3A%22k6_sse_mutate_created_rate%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785300990000%22%2C%22to%22%3A%221785302940000%22%7D%7D%7D

