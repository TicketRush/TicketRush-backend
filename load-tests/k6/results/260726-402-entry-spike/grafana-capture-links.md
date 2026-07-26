# Grafana Explore 캡처 링크 (#402)

측정 창: 2026-07-26 18:56 ~ 19:12 KST
SSH 터널(-L 3000:localhost:3000)이 떠 있어야 한다.

## graph-latency-p95.png — 검표 지연 p95 (qr/verify/checkin)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%221000%2Ak6_entry_qr_duration_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%2Ak6_entry_verify_duration_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%221000%2Ak6_entry_checkin_duration_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785059760000%22%2C%22to%22%3A%221785060720000%22%7D%7D%7D

## graph-rps-cpu.png — 유입 RPS + 호스트 CPU

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28k6_http_reqs_total%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22100%20-%20%28avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%20%2A%20100%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785059760000%22%2C%22to%22%3A%221785060720000%22%7D%7D%7D

## graph-server-avg.png — 서버 측 엔드포인트별 평균 지연

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%221000%20%2A%20%28sum%20by%20%28uri%29%20%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22ticket-service%3A8090%5C%22%2Curi%3D~%5C%22/api/v1/%28entries/.%2A%7Cticket/bookings/.%2A%29%5C%22%7D%5B1m%5D%29%29%20/%20sum%20by%20%28uri%29%20%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22ticket-service%3A8090%5C%22%2Curi%3D~%5C%22/api/v1/%28entries/.%2A%7Cticket/bookings/.%2A%29%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785059760000%22%2C%22to%22%3A%221785060720000%22%7D%7D%7D

## graph-booking-internal.png — booking 내부조회 RPS + 평균 지연

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22booking-service%3A8090%5C%22%2Curi%3D%5C%22/api/v1/internal/booking/%7BbookingId%7D%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20%28sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22booking-service%3A8090%5C%22%2Curi%3D%5C%22/api/v1/internal/booking/%7BbookingId%7D%5C%22%7D%5B1m%5D%29%29%20/%20sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22booking-service%3A8090%5C%22%2Curi%3D%5C%22/api/v1/internal/booking/%7BbookingId%7D%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785059760000%22%2C%22to%22%3A%221785060720000%22%7D%7D%7D
