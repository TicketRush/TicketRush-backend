# #598 Grafana 캡처 링크

> ⚠ **이 회차의 곡선 증적은 캡처가 아니라 `graph-*.svg` 다.** 아래 링크는 보조 수단으로 남긴다.
>
> **왜 바꿨나**
> 1. **브라우저 자동화가 안정적으로 안 됐다.** Grafana Explore 페이지에서 CDP `Page.captureScreenshot` 이 5회 중 4회 렌더러 타임아웃으로 실패했다(1장만 성공).
> 2. **캡처는 15일 뒤 재현이 안 된다.** Prometheus 보존이 15일이라 2026-08-28 이후 이 링크들은 빈 화면이다. `graph-*.svg` 는 커밋된 `timeseries-*.json` 에서 `load-test/bench/plot-timeseries.py` 로 언제든 다시 만들 수 있다.
> 3. **그림과 수치의 출처가 같아진다.** SVG 는 리포트가 인용하는 그 JSON 에서 직접 그린다 — 둘이 어긋날 수 없다. 캡처는 다른 창을 찍어도 아무도 모른다.
>
> **여전히 캡처가 나은 경우**: Grafana 에만 있는 것(패널 옵션·주석·대시보드 문맥)을 보여줘야 할 때. 그때 아래 링크를 쓴다.
>
> ⚠ `#554` 가 남긴 *"캡처는 덤프의 그림 버전이 아니다"* 라는 경고는 **집계 스크립트의 min/avg/max 요약**에 대한 것이었다. `timeseries-*.json` 자체에는 모든 표본이 들어 있어 곡선 정보가 손실되지 않는다 — 이 회차의 HikariCP 발견도 그림이 아니라 그 JSON 을 다시 읽어서 잡았다(`report.md` §9.7).


회차 디렉터리: `load-tests/k6/results/260813-598-consumer-concurrency/`

## 캡처 절차

```bash
ssh -i <key>.pem -L 3000:localhost:3000 -L 9090:localhost:9090 ubuntu@54.116.243.250
```

Grafana(<http://localhost:3000>)에 **먼저 로그인**한 뒤 아래 링크를 연다. 로그인 전에 열면 로그인 화면으로 튕기면서 쿼리와 시간 범위가 날아간다.

⚠ Prometheus 보존 기간 15일 — **2026-08-28 이후에는 링크가 빈 화면**이 되고 `timeseries-*.json` 덤프만 남는다.

## S1C1 arm — 2026-08-13T14:59:00Z ~ 2026-08-13T15:13:30Z

## S1C1 arm — 2026-08-13T14:59:00Z ~ 2026-08-13T15:13:30Z

### 예매 서버 RPS vs VU
저장 파일명: `graph-booking-rps-vs-vus-s1c1.png`

> B 는 실제 VU ÷ 500 (2.0 = 1,000 VU)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22booking-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22k6_vus%20%2F%20500%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786633140000%22%2C%22to%22%3A%221786634010000%22%7D%7D%7D

### 게이트웨이 총 RPS — 대기열의 자기 비용
저장 파일명: `graph-gateway-rps-s1c1.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22gateway-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786633140000%22%2C%22to%22%3A%221786634010000%22%7D%7D%7D

### 호스트 CPU
저장 파일명: `graph-host-cpu-s1c1.png`

> 무부하 기저는 약 7%

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20-%20%28avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%20%2A%20100%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786633140000%22%2C%22to%22%3A%221786634010000%22%7D%7D%7D

### 호스트 CPU 모드 분해 — iowait 은 작업이 아니다
저장 파일명: `graph-host-cpu-modes-s1c1.png`

> user
> system
> iowait — 이 폭이 곧 스레드를 늘려 채울 수 있는 여유다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22user%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22100%20%2A%20avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22system%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22100%20%2A%20avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22iowait%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786633140000%22%2C%22to%22%3A%221786634010000%22%7D%7D%7D

### Kafka 컨슈머 랙 — 랙 유무 신호로만
저장 파일명: `graph-kafka-consumer-lag-s1c1.png`

> 절대량·곡선 판단 금지. backlog SSOT 는 브로커 축이다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22max%20by%20%28instance%2C%20topic%29%20%28kafka_consumer_fetch_manager_records_lag%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786633140000%22%2C%22to%22%3A%221786634010000%22%7D%7D%7D

### 컨슈머 스레드 — concurrency 가 실제로 먹었는지
저장 파일명: `graph-consumer-threads-s1c1.png`

> A arm 에서 리스너 수 × 2 만큼 줄어야 한다 (booking 5 · seat 3 · ticket 2 · payment 3)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22jvm_threads_live_threads%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786633140000%22%2C%22to%22%3A%221786634010000%22%7D%7D%7D

### 컨테이너 메모리 % — seat/booking/gateway
저장 파일명: `graph-container-mem-s1c1.png`

> 라벨은 name= 이 아니라 container= 다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D~%5C%22seat-service%7Cbooking-service%7Cgateway-service%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D~%5C%22seat-service%7Cbooking-service%7Cgateway-service%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786633140000%22%2C%22to%22%3A%221786634010000%22%7D%7D%7D

### 컨테이너 메모리 % — MySQL/Kafka/Redis
저장 파일명: `graph-container-mem-infra-s1c1.png`

> MySQL 이 100% 에 닿아도 OOM kill 이 0 이면 정상 운영값이다 — kill 카운터와 함께 읽는다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D~%5C%22ticketrush-mysql%7Cticketrush-kafka%7Cticketrush-redis%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D~%5C%22ticketrush-mysql%7Cticketrush-kafka%7Cticketrush-redis%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786633140000%22%2C%22to%22%3A%221786634010000%22%7D%7D%7D

### HikariCP active/pending · Tomcat busy
저장 파일명: `graph-hikari-tomcat-s1c1.png`

> seat 는 max 50, 나머지 200

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22hikaricp_connections_active%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22hikaricp_connections_pending%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786633140000%22%2C%22to%22%3A%221786634010000%22%7D%7D%7D

### 동시 커넥션 (Tcp_CurrEstab)
저장 파일명: `graph-connections-s1c1.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22node_netstat_Tcp_CurrEstab%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786633140000%22%2C%22to%22%3A%221786634010000%22%7D%7D%7D

### outbox backlog — 무너진 뒤 회복
저장 파일명: `graph-outbox-backlog-s1c1.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_outbox_backlog%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786633140000%22%2C%22to%22%3A%221786634010000%22%7D%7D%7D

### Redis 메모리 (무효 기준 48MB)
저장 파일명: `graph-redis-mem-s1c1.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22redis_memory_used_bytes%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786633140000%22%2C%22to%22%3A%221786634010000%22%7D%7D%7D

## S2C1 arm — 2026-08-13T15:15:00Z ~ 2026-08-13T15:24:00Z

### 예매 서버 RPS vs VU
저장 파일명: `graph-booking-rps-vs-vus-s2c1.png`

> B 는 실제 VU ÷ 500 (2.0 = 1,000 VU)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22booking-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22k6_vus%20%2F%20500%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786634100000%22%2C%22to%22%3A%221786634640000%22%7D%7D%7D

### 게이트웨이 총 RPS — 대기열의 자기 비용
저장 파일명: `graph-gateway-rps-s2c1.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22gateway-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786634100000%22%2C%22to%22%3A%221786634640000%22%7D%7D%7D

### 호스트 CPU
저장 파일명: `graph-host-cpu-s2c1.png`

> 무부하 기저는 약 7%

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20-%20%28avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%20%2A%20100%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786634100000%22%2C%22to%22%3A%221786634640000%22%7D%7D%7D

### 호스트 CPU 모드 분해 — iowait 은 작업이 아니다
저장 파일명: `graph-host-cpu-modes-s2c1.png`

> user
> system
> iowait — 이 폭이 곧 스레드를 늘려 채울 수 있는 여유다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22user%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22100%20%2A%20avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22system%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22100%20%2A%20avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22iowait%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786634100000%22%2C%22to%22%3A%221786634640000%22%7D%7D%7D

### Kafka 컨슈머 랙 — 랙 유무 신호로만
저장 파일명: `graph-kafka-consumer-lag-s2c1.png`

> 절대량·곡선 판단 금지. backlog SSOT 는 브로커 축이다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22max%20by%20%28instance%2C%20topic%29%20%28kafka_consumer_fetch_manager_records_lag%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786634100000%22%2C%22to%22%3A%221786634640000%22%7D%7D%7D

### 컨슈머 스레드 — concurrency 가 실제로 먹었는지
저장 파일명: `graph-consumer-threads-s2c1.png`

> A arm 에서 리스너 수 × 2 만큼 줄어야 한다 (booking 5 · seat 3 · ticket 2 · payment 3)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22jvm_threads_live_threads%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786634100000%22%2C%22to%22%3A%221786634640000%22%7D%7D%7D

### 컨테이너 메모리 % — seat/booking/gateway
저장 파일명: `graph-container-mem-s2c1.png`

> 라벨은 name= 이 아니라 container= 다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D~%5C%22seat-service%7Cbooking-service%7Cgateway-service%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D~%5C%22seat-service%7Cbooking-service%7Cgateway-service%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786634100000%22%2C%22to%22%3A%221786634640000%22%7D%7D%7D

### 컨테이너 메모리 % — MySQL/Kafka/Redis
저장 파일명: `graph-container-mem-infra-s2c1.png`

> MySQL 이 100% 에 닿아도 OOM kill 이 0 이면 정상 운영값이다 — kill 카운터와 함께 읽는다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D~%5C%22ticketrush-mysql%7Cticketrush-kafka%7Cticketrush-redis%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D~%5C%22ticketrush-mysql%7Cticketrush-kafka%7Cticketrush-redis%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786634100000%22%2C%22to%22%3A%221786634640000%22%7D%7D%7D

### HikariCP active/pending · Tomcat busy
저장 파일명: `graph-hikari-tomcat-s2c1.png`

> seat 는 max 50, 나머지 200

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22hikaricp_connections_active%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22hikaricp_connections_pending%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786634100000%22%2C%22to%22%3A%221786634640000%22%7D%7D%7D

### 동시 커넥션 (Tcp_CurrEstab)
저장 파일명: `graph-connections-s2c1.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22node_netstat_Tcp_CurrEstab%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786634100000%22%2C%22to%22%3A%221786634640000%22%7D%7D%7D

### outbox backlog — 무너진 뒤 회복
저장 파일명: `graph-outbox-backlog-s2c1.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_outbox_backlog%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786634100000%22%2C%22to%22%3A%221786634640000%22%7D%7D%7D

### Redis 메모리 (무효 기준 48MB)
저장 파일명: `graph-redis-mem-s2c1.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22redis_memory_used_bytes%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786634100000%22%2C%22to%22%3A%221786634640000%22%7D%7D%7D

## S1C3 arm — 2026-08-13T15:45:00Z ~ 2026-08-13T15:55:30Z

### 예매 서버 RPS vs VU
저장 파일명: `graph-booking-rps-vs-vus-s1c3.png`

> B 는 실제 VU ÷ 500 (2.0 = 1,000 VU)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22booking-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22k6_vus%20%2F%20500%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786635900000%22%2C%22to%22%3A%221786636530000%22%7D%7D%7D

### 게이트웨이 총 RPS — 대기열의 자기 비용
저장 파일명: `graph-gateway-rps-s1c3.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22gateway-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786635900000%22%2C%22to%22%3A%221786636530000%22%7D%7D%7D

### 호스트 CPU
저장 파일명: `graph-host-cpu-s1c3.png`

> 무부하 기저는 약 7%

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20-%20%28avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%20%2A%20100%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786635900000%22%2C%22to%22%3A%221786636530000%22%7D%7D%7D

### 호스트 CPU 모드 분해 — iowait 은 작업이 아니다
저장 파일명: `graph-host-cpu-modes-s1c3.png`

> user
> system
> iowait — 이 폭이 곧 스레드를 늘려 채울 수 있는 여유다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22user%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22100%20%2A%20avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22system%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22100%20%2A%20avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22iowait%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786635900000%22%2C%22to%22%3A%221786636530000%22%7D%7D%7D

### Kafka 컨슈머 랙 — 랙 유무 신호로만
저장 파일명: `graph-kafka-consumer-lag-s1c3.png`

> 절대량·곡선 판단 금지. backlog SSOT 는 브로커 축이다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22max%20by%20%28instance%2C%20topic%29%20%28kafka_consumer_fetch_manager_records_lag%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786635900000%22%2C%22to%22%3A%221786636530000%22%7D%7D%7D

### 컨슈머 스레드 — concurrency 가 실제로 먹었는지
저장 파일명: `graph-consumer-threads-s1c3.png`

> A arm 에서 리스너 수 × 2 만큼 줄어야 한다 (booking 5 · seat 3 · ticket 2 · payment 3)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22jvm_threads_live_threads%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786635900000%22%2C%22to%22%3A%221786636530000%22%7D%7D%7D

### 컨테이너 메모리 % — seat/booking/gateway
저장 파일명: `graph-container-mem-s1c3.png`

> 라벨은 name= 이 아니라 container= 다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D~%5C%22seat-service%7Cbooking-service%7Cgateway-service%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D~%5C%22seat-service%7Cbooking-service%7Cgateway-service%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786635900000%22%2C%22to%22%3A%221786636530000%22%7D%7D%7D

### 컨테이너 메모리 % — MySQL/Kafka/Redis
저장 파일명: `graph-container-mem-infra-s1c3.png`

> MySQL 이 100% 에 닿아도 OOM kill 이 0 이면 정상 운영값이다 — kill 카운터와 함께 읽는다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D~%5C%22ticketrush-mysql%7Cticketrush-kafka%7Cticketrush-redis%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D~%5C%22ticketrush-mysql%7Cticketrush-kafka%7Cticketrush-redis%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786635900000%22%2C%22to%22%3A%221786636530000%22%7D%7D%7D

### HikariCP active/pending · Tomcat busy
저장 파일명: `graph-hikari-tomcat-s1c3.png`

> seat 는 max 50, 나머지 200

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22hikaricp_connections_active%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22hikaricp_connections_pending%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786635900000%22%2C%22to%22%3A%221786636530000%22%7D%7D%7D

### 동시 커넥션 (Tcp_CurrEstab)
저장 파일명: `graph-connections-s1c3.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22node_netstat_Tcp_CurrEstab%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786635900000%22%2C%22to%22%3A%221786636530000%22%7D%7D%7D

### outbox backlog — 무너진 뒤 회복
저장 파일명: `graph-outbox-backlog-s1c3.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_outbox_backlog%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786635900000%22%2C%22to%22%3A%221786636530000%22%7D%7D%7D

### Redis 메모리 (무효 기준 48MB)
저장 파일명: `graph-redis-mem-s1c3.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22redis_memory_used_bytes%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786635900000%22%2C%22to%22%3A%221786636530000%22%7D%7D%7D

## S2C3 arm — 2026-08-13T15:56:00Z ~ 2026-08-13T16:05:00Z

### 예매 서버 RPS vs VU
저장 파일명: `graph-booking-rps-vs-vus-s2c3.png`

> B 는 실제 VU ÷ 500 (2.0 = 1,000 VU)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22booking-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22k6_vus%20%2F%20500%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786636560000%22%2C%22to%22%3A%221786637100000%22%7D%7D%7D

### 게이트웨이 총 RPS — 대기열의 자기 비용
저장 파일명: `graph-gateway-rps-s2c3.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22gateway-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786636560000%22%2C%22to%22%3A%221786637100000%22%7D%7D%7D

### 호스트 CPU
저장 파일명: `graph-host-cpu-s2c3.png`

> 무부하 기저는 약 7%

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20-%20%28avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%20%2A%20100%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786636560000%22%2C%22to%22%3A%221786637100000%22%7D%7D%7D

### 호스트 CPU 모드 분해 — iowait 은 작업이 아니다
저장 파일명: `graph-host-cpu-modes-s2c3.png`

> user
> system
> iowait — 이 폭이 곧 스레드를 늘려 채울 수 있는 여유다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22user%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22100%20%2A%20avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22system%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22100%20%2A%20avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22iowait%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786636560000%22%2C%22to%22%3A%221786637100000%22%7D%7D%7D

### Kafka 컨슈머 랙 — 랙 유무 신호로만
저장 파일명: `graph-kafka-consumer-lag-s2c3.png`

> 절대량·곡선 판단 금지. backlog SSOT 는 브로커 축이다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22max%20by%20%28instance%2C%20topic%29%20%28kafka_consumer_fetch_manager_records_lag%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786636560000%22%2C%22to%22%3A%221786637100000%22%7D%7D%7D

### 컨슈머 스레드 — concurrency 가 실제로 먹었는지
저장 파일명: `graph-consumer-threads-s2c3.png`

> A arm 에서 리스너 수 × 2 만큼 줄어야 한다 (booking 5 · seat 3 · ticket 2 · payment 3)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22jvm_threads_live_threads%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786636560000%22%2C%22to%22%3A%221786637100000%22%7D%7D%7D

### 컨테이너 메모리 % — seat/booking/gateway
저장 파일명: `graph-container-mem-s2c3.png`

> 라벨은 name= 이 아니라 container= 다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D~%5C%22seat-service%7Cbooking-service%7Cgateway-service%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D~%5C%22seat-service%7Cbooking-service%7Cgateway-service%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786636560000%22%2C%22to%22%3A%221786637100000%22%7D%7D%7D

### 컨테이너 메모리 % — MySQL/Kafka/Redis
저장 파일명: `graph-container-mem-infra-s2c3.png`

> MySQL 이 100% 에 닿아도 OOM kill 이 0 이면 정상 운영값이다 — kill 카운터와 함께 읽는다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D~%5C%22ticketrush-mysql%7Cticketrush-kafka%7Cticketrush-redis%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D~%5C%22ticketrush-mysql%7Cticketrush-kafka%7Cticketrush-redis%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786636560000%22%2C%22to%22%3A%221786637100000%22%7D%7D%7D

### HikariCP active/pending · Tomcat busy
저장 파일명: `graph-hikari-tomcat-s2c3.png`

> seat 는 max 50, 나머지 200

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22hikaricp_connections_active%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22hikaricp_connections_pending%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786636560000%22%2C%22to%22%3A%221786637100000%22%7D%7D%7D

### 동시 커넥션 (Tcp_CurrEstab)
저장 파일명: `graph-connections-s2c3.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22node_netstat_Tcp_CurrEstab%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786636560000%22%2C%22to%22%3A%221786637100000%22%7D%7D%7D

### outbox backlog — 무너진 뒤 회복
저장 파일명: `graph-outbox-backlog-s2c3.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_outbox_backlog%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786636560000%22%2C%22to%22%3A%221786637100000%22%7D%7D%7D

### Redis 메모리 (무효 기준 48MB)
저장 파일명: `graph-redis-mem-s2c3.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22redis_memory_used_bytes%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786636560000%22%2C%22to%22%3A%221786637100000%22%7D%7D%7D

