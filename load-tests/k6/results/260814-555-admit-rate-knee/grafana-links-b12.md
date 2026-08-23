## B12 arm — 2026-08-14T11:35:19Z ~ 2026-08-14T11:45:29Z

### 예매 서버 RPS vs VU
저장 파일명: `graph-booking-rps-vs-vus-b12.png`

> B 는 실제 VU ÷ 500 (2.0 = 1,000 VU)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22booking-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22k6_vus%20%2F%20500%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786707319000%22%2C%22to%22%3A%221786707929000%22%7D%7D%7D

### 게이트웨이 총 RPS — 대기열의 자기 비용
저장 파일명: `graph-gateway-rps-b12.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22gateway-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786707319000%22%2C%22to%22%3A%221786707929000%22%7D%7D%7D

### 호스트 CPU
저장 파일명: `graph-host-cpu-b12.png`

> 무부하 기저는 약 7%

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20-%20%28avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%20%2A%20100%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786707319000%22%2C%22to%22%3A%221786707929000%22%7D%7D%7D

### 호스트 CPU 모드 분해 — iowait 은 작업이 아니다
저장 파일명: `graph-host-cpu-modes-b12.png`

> user
> system
> iowait — 이 폭이 곧 스레드를 늘려 채울 수 있는 여유다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22user%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22100%20%2A%20avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22system%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22100%20%2A%20avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22iowait%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786707319000%22%2C%22to%22%3A%221786707929000%22%7D%7D%7D

### Kafka 컨슈머 랙 — 랙 유무 신호로만
저장 파일명: `graph-kafka-consumer-lag-b12.png`

> 절대량·곡선 판단 금지. backlog SSOT 는 브로커 축이다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22max%20by%20%28instance%2C%20topic%29%20%28kafka_consumer_fetch_manager_records_lag%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786707319000%22%2C%22to%22%3A%221786707929000%22%7D%7D%7D

### 컨슈머 스레드 — concurrency 가 실제로 먹었는지
저장 파일명: `graph-consumer-threads-b12.png`

> A arm 에서 리스너 수 × 2 만큼 줄어야 한다 (booking 5 · seat 3 · ticket 2 · payment 3)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22jvm_threads_live_threads%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786707319000%22%2C%22to%22%3A%221786707929000%22%7D%7D%7D

### 컨테이너 메모리 % — seat/booking/gateway
저장 파일명: `graph-container-mem-b12.png`

> 라벨은 name= 이 아니라 container= 다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D~%5C%22seat-service%7Cbooking-service%7Cgateway-service%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D~%5C%22seat-service%7Cbooking-service%7Cgateway-service%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786707319000%22%2C%22to%22%3A%221786707929000%22%7D%7D%7D

### 컨테이너 메모리 % — MySQL/Kafka/Redis
저장 파일명: `graph-container-mem-infra-b12.png`

> MySQL 이 100% 에 닿아도 OOM kill 이 0 이면 정상 운영값이다 — kill 카운터와 함께 읽는다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D~%5C%22ticketrush-mysql%7Cticketrush-kafka%7Cticketrush-redis%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D~%5C%22ticketrush-mysql%7Cticketrush-kafka%7Cticketrush-redis%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786707319000%22%2C%22to%22%3A%221786707929000%22%7D%7D%7D

### HikariCP active/pending · Tomcat busy
저장 파일명: `graph-hikari-tomcat-b12.png`

> seat 는 max 50, 나머지 200

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22hikaricp_connections_active%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22hikaricp_connections_pending%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786707319000%22%2C%22to%22%3A%221786707929000%22%7D%7D%7D

### 동시 커넥션 (Tcp_CurrEstab)
저장 파일명: `graph-connections-b12.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22node_netstat_Tcp_CurrEstab%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786707319000%22%2C%22to%22%3A%221786707929000%22%7D%7D%7D

### outbox backlog — 무너진 뒤 회복
저장 파일명: `graph-outbox-backlog-b12.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_outbox_backlog%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786707319000%22%2C%22to%22%3A%221786707929000%22%7D%7D%7D

### Redis 메모리 (무효 기준 48MB)
저장 파일명: `graph-redis-mem-b12.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22redis_memory_used_bytes%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786707319000%22%2C%22to%22%3A%221786707929000%22%7D%7D%7D

