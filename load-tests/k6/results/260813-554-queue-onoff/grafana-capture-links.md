# Grafana Explore 캡처 링크 — #554 ON/OFF 대조 회차

두 arm 을 **같은 축·같은 방식**으로 찍기 위한 링크다. 쿼리와 UTC 절대 시각이 URL 에 박혀 있다
(`load-test/bench/grafana-links.py` 가 생성 — 손으로 인코딩하지 않는다).

## 캡처 절차

Grafana 에 이미지 렌더러 플러그인이 없어 **PNG 를 서버가 만들어 주지 못한다.**

1. SSH 터널: `ssh -i <key>.pem -L 3000:localhost:3000 -L 9090:localhost:9090 ubuntu@54.116.243.250`
2. **Grafana 에 먼저 로그인한다** (http://localhost:3000). 안 하면 링크가 로그인 화면으로 튕기고 쿼리·시간범위가 유실된다.
3. 아래 링크를 그대로 연다.
4. 브라우저 캡처 → 지정된 파일명으로 이 디렉토리에 저장.
5. PR 본문의 `📸 스크린샷` 절을 갱신한다.

> ⚠️ **Prometheus 보존은 15일이다. 2026-08-28 이후에는 이 링크들이 빈 그래프가 된다.**
> 그때는 같은 디렉토리의 `timeseries-*.json` 덤프가 유일한 원자료다.

> ⚠️ 그래프 시각은 브라우저 시간대(KST)로 보인다. 이 문서와 리포트는 전부 **UTC** 다 — 9시간을 뺀다.

> ⚠️ **ON/OFF 를 한 패널에 겹치지 않는다.** 게이트웨이 RPS 가 444.6 vs 100.5 로 4.4배 차이라
> 같은 축에 얹으면 작은 쪽이 눌린다. #529 의 before/after 관행대로 **arm 별 두 장**으로 찍는다.

---

## ON arm — 2026-08-13T10:57:48Z ~ 2026-08-13T11:22:00Z

### 예매 서버 RPS vs VU
저장 파일명: `graph-booking-rps-vs-vus-on.png`

> B 는 실제 VU ÷ 500 (2.0 = 1,000 VU)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22booking-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22k6_vus%20%2F%20500%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786618668000%22%2C%22to%22%3A%221786620120000%22%7D%7D%7D

### 게이트웨이 총 RPS — 대기열의 자기 비용
저장 파일명: `graph-gateway-rps-on.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22gateway-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786618668000%22%2C%22to%22%3A%221786620120000%22%7D%7D%7D

### 호스트 CPU
저장 파일명: `graph-host-cpu-on.png`

> 무부하 기저는 약 7%

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20-%20%28avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%20%2A%20100%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786618668000%22%2C%22to%22%3A%221786620120000%22%7D%7D%7D

### 대기열 승급 — result 별
저장 파일명: `graph-queue-admission-on.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%20by%20%28result%29%20%28rate%28ticketrush_queue_admission_total%7Bjob%3D%5C%22gateway%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786618668000%22%2C%22to%22%3A%221786620120000%22%7D%7D%7D

### 컨테이너 메모리 % — seat/booking/gateway
저장 파일명: `graph-container-mem-on.png`

> 라벨은 name= 이 아니라 container= 다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D~%5C%22seat-service%7Cbooking-service%7Cgateway-service%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D~%5C%22seat-service%7Cbooking-service%7Cgateway-service%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786618668000%22%2C%22to%22%3A%221786620120000%22%7D%7D%7D

### HikariCP active/pending · Tomcat busy
저장 파일명: `graph-hikari-tomcat-on.png`

> seat 는 max 50, 나머지 200

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22hikaricp_connections_active%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22hikaricp_connections_pending%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786618668000%22%2C%22to%22%3A%221786620120000%22%7D%7D%7D

### 동시 커넥션 (Tcp_CurrEstab)
저장 파일명: `graph-connections-on.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22node_netstat_Tcp_CurrEstab%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786618668000%22%2C%22to%22%3A%221786620120000%22%7D%7D%7D

### outbox backlog — 무너진 뒤 회복
저장 파일명: `graph-outbox-backlog-on.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_outbox_backlog%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786618668000%22%2C%22to%22%3A%221786620120000%22%7D%7D%7D

### Redis 메모리 (무효 기준 48MB)
저장 파일명: `graph-redis-mem-on.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22redis_memory_used_bytes%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786618668000%22%2C%22to%22%3A%221786620120000%22%7D%7D%7D


---

## OFF arm — 2026-08-13T11:24:24Z ~ 2026-08-13T11:50:00Z

### 예매 서버 RPS vs VU
저장 파일명: `graph-booking-rps-vs-vus-off.png`

> B 는 실제 VU ÷ 500 (2.0 = 1,000 VU)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22booking-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22k6_vus%20%2F%20500%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786620264000%22%2C%22to%22%3A%221786621800000%22%7D%7D%7D

### 게이트웨이 총 RPS — 대기열의 자기 비용
저장 파일명: `graph-gateway-rps-off.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22gateway-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786620264000%22%2C%22to%22%3A%221786621800000%22%7D%7D%7D

### 호스트 CPU
저장 파일명: `graph-host-cpu-off.png`

> 무부하 기저는 약 7%

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20-%20%28avg%28rate%28node_cpu_seconds_total%7Bmode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%20%2A%20100%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786620264000%22%2C%22to%22%3A%221786621800000%22%7D%7D%7D

### 대기열 승급 — result 별
저장 파일명: `graph-queue-admission-off.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%20by%20%28result%29%20%28rate%28ticketrush_queue_admission_total%7Bjob%3D%5C%22gateway%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786620264000%22%2C%22to%22%3A%221786621800000%22%7D%7D%7D

### 컨테이너 메모리 % — seat/booking/gateway
저장 파일명: `graph-container-mem-off.png`

> 라벨은 name= 이 아니라 container= 다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D~%5C%22seat-service%7Cbooking-service%7Cgateway-service%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D~%5C%22seat-service%7Cbooking-service%7Cgateway-service%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786620264000%22%2C%22to%22%3A%221786621800000%22%7D%7D%7D

### HikariCP active/pending · Tomcat busy
저장 파일명: `graph-hikari-tomcat-off.png`

> seat 는 max 50, 나머지 200

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22hikaricp_connections_active%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22hikaricp_connections_pending%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786620264000%22%2C%22to%22%3A%221786621800000%22%7D%7D%7D

### 동시 커넥션 (Tcp_CurrEstab)
저장 파일명: `graph-connections-off.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22node_netstat_Tcp_CurrEstab%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786620264000%22%2C%22to%22%3A%221786621800000%22%7D%7D%7D

### outbox backlog — 무너진 뒤 회복
저장 파일명: `graph-outbox-backlog-off.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_outbox_backlog%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786620264000%22%2C%22to%22%3A%221786621800000%22%7D%7D%7D

### Redis 메모리 (무효 기준 48MB)
저장 파일명: `graph-redis-mem-off.png`


http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22redis_memory_used_bytes%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786620264000%22%2C%22to%22%3A%221786621800000%22%7D%7D%7D


---

## 📌 추가 캡처 (2026-08-13 사후) — 인프라 컨테이너 메모리

**위 18장을 찍은 뒤에 누락이 확인돼 추가한다.** 기존 `컨테이너 메모리` 패널이 `seat/booking/gateway` 3개만 필터해서, **OFF arm 에서 MySQL 이 100% 에 닿은 사실이 그림으로 남지 않았다**(리포트 §2.3). 아래 2장을 추가로 찍으면 대조표의 그 행이 캡처로도 뒷받침된다.

> ⚠️ **MySQL 이 100% 라도 그 자체가 이상은 아니다.** `mem_limit` 안에서 버퍼풀·캐시가 차오른 것이고 이 회차의 OOM kill 은 0건이었다(ADR 0006). 그림을 인용할 때 이 문장을 함께 적는다.

> ⚠️ 위 18장과 **같은 시간 창**을 쓴다(ON `10:57:48Z-11:22:00Z` / OFF `11:24:24Z-11:50:00Z`). 창이 다르면 대조가 성립하지 않는다.

### 컨테이너 메모리 % — MySQL/Kafka/Redis (ON arm)
저장 파일명: `graph-container-mem-infra-on.png`

> MySQL 이 100% 에 닿아도 OOM kill 이 0 이면 정상 운영값이다 — kill 카운터와 함께 읽는다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D~%5C%22ticketrush-mysql%7Cticketrush-kafka%7Cticketrush-redis%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D~%5C%22ticketrush-mysql%7Cticketrush-kafka%7Cticketrush-redis%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786618668000%22%2C%22to%22%3A%221786620120000%22%7D%7D%7D

### 컨테이너 메모리 % — MySQL/Kafka/Redis (OFF arm)
저장 파일명: `graph-container-mem-infra-off.png`

> MySQL 이 100% 에 닿아도 OOM kill 이 0 이면 정상 운영값이다 — kill 카운터와 함께 읽는다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20ticketrush_container_memory_usage_bytes%7Bcontainer%3D~%5C%22ticketrush-mysql%7Cticketrush-kafka%7Cticketrush-redis%5C%22%7D%20%2F%20ticketrush_container_memory_limit_bytes%7Bcontainer%3D~%5C%22ticketrush-mysql%7Cticketrush-kafka%7Cticketrush-redis%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221786620264000%22%2C%22to%22%3A%221786621800000%22%7D%7D%7D

