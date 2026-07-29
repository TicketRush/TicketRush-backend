# Grafana Explore 캡처 링크 (#504)

이 회차의 증적 그래프를 찍기 위한 Grafana Explore 링크다. 쿼리와 측정 창이 URL 에 박혀 있으므로 손으로 맞출 필요가 없다.

## 캡처 절차

Grafana 에 이미지 렌더러 플러그인이 없어 **PNG 를 서버가 만들어 주지 못한다.** 아래 링크를 열어
화면을 직접 캡처해 같은 디렉토리에 지정된 파일명으로 저장한다(#403 회차와 같은 관행).

1. **SSH 터널을 올린다.** Grafana(3000)·Prometheus(9090)는 `127.0.0.1` 바인딩이라
   인터넷에서 접근할 수 없다(ADR 0007, 런북 §7.1).
   ```bash
   ssh -i <key>.pem -L 3000:localhost:3000 -L 9090:localhost:9090 ubuntu@<EC2_탄력적_IP>
   ```
2. **Grafana 에 먼저 로그인한다.** http://localhost:3000 (기본 `admin`/`admin`).
   로그인하지 않은 채 Explore 링크를 열면 로그인 화면으로 튕기고, 로그인 후에는
   쿼리·시간 범위가 유실된다.
3. **아래 링크를 그대로 연다.** datasource·쿼리·시간 범위(UTC 절대 시각)가 모두 박혀 있다.
   회차 창이 어긋나면 그래프가 다른 것을 말한다.
4. **브라우저 캡처로 저장한다.** 파일명은 각 절의 제목에 적힌 이름 그대로, 저장 위치는
   이 파일과 같은 디렉토리다.
5. 다 찍으면 `report.md` 의 완료조건 대조표를 갱신한다.

> ⚠️ **부하 측정 중에는 Grafana 를 열지 않는다.** 패널마다 주기적으로 쿼리가 나가 측정 대상의
> CPU 를 소모한다(런북 §7.2). 데이터는 TSDB 에 남으므로 회차가 끝난 뒤에 열어 본다.

> Prometheus 보존은 15일이다. 창이 보존 기간 밖으로 밀려나면 이 링크들은 빈 그래프가 되고,
> 그때는 같은 디렉토리의 `timeseries-*.json` 덤프가 유일한 원자료다(그래서 함께 커밋한다).

### 이 회차의 캡처 함정

- **`sum` 과 `max` 를 섞어 읽지 않는다.** 대시보드의 `Kafka Consumer Lag` 패널과 런북 §10.3 은
  `max by (instance, topic)` 인데, 그건 **파티션 3개 중 최댓값**이지 총 적체가 아니다. 회복량과
  기울기를 읽으려면 `sum by (...)` 여야 한다. `graph-partition-skew.png` 만 둘을 나란히 둔다.
- **두 그룹은 같은 축에 둬도 된다.** booking-group 과 ticket-group 의 적체는 같은 단위(건수)이고
  자릿수도 같다 — #403 이 `k6_vus`(0~600)와 전파 지연(1.2~4.0)을 한 축에 넣어 다시 찍어야 했던
  상황과 다르다.
- **`ticketrush_ticket_issue_total` 은 스모크 전에는 시계열이 없다.** Micrometer 지연 등록이라
  첫 발급이 일어나야 생긴다. 창을 스모크 시작 이전으로 넓히면 앞부분이 비어 보인다.


## 캡처 목록

**2장이다.** 이 회차의 본 그림인 backlog 회복 곡선은 **Grafana 로 그리지 않는다** — `kafka_consumer_fetch_manager_records_lag` 가 파티션별 "마지막 fetch 응답 시점의 값"이라 단일 스레드가 파티션 3개를 훑는 이 구성에서는 단조 감소하지 않고 톱니로 튄다(report.md §7.6). 회복 곡선의 원자료는 브로커 축인 `lag-samples-spike.csv` 이고, 파티션 분포는 브로커의 파티션별 LOG-END-OFFSET(9,559 / 9,740 / 9,702)으로 판정했다. 아래 2장은 **서버 카운터와 자원 축**이라 스크랩 시점 문제가 없다.

### graph-drain-rate.png — 두 그룹의 처리율. ticket-group 이 먼저 0에 닿고(07:25:12Z) 그 뒤 booking-group 이 뛰는 것이 보인다 — §7.2·§7.3 의 그림

측정 창: 2026-07-29T07:15:30Z ~ 2026-07-29T07:31:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%20by%20%28consumer_group%2C%20result%29%20%28rate%28ticketrush_kafka_inbox_total%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22sum%20by%20%28result%29%20%28rate%28ticketrush_ticket_issue_total%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785309330000%22%2C%22to%22%3A%221785310260000%22%7D%7D%7D

### graph-resources.png — 회복 구간의 호스트 CPU·HikariCP 대기·seat-service 톰캣 스레드. 병목이 어느 풀도 아니라는 근거 — §7.4

측정 창: 2026-07-29T07:15:30Z ~ 2026-07-29T07:31:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20%281%20-%20avg%28rate%28node_cpu_seconds_total%7Bjob%3D%5C%22node%5C%22%2C%20mode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22hikaricp_connections_pending%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785309330000%22%2C%22to%22%3A%221785310260000%22%7D%7D%7D
