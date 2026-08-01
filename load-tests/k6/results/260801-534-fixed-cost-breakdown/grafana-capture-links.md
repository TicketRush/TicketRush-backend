# Grafana Explore 캡처 링크 (#534)

이 회차의 증적 그래프를 찍기 위한 Grafana Explore 링크다. 쿼리와 측정 창이 URL 에 박혀 있으므로 손으로 맞출 필요가 없다.

## 캡처 절차

Grafana 에 이미지 렌더러 플러그인이 없어 **PNG 를 서버가 만들어 주지 못한다.** 아래 링크를 열어
화면을 직접 캡처해 같은 디렉토리에 지정된 파일명으로 저장한다(#403·#504·#529 회차와 같은 관행).

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

> ⚠️ **EC2 는 세션 사이에 정지돼 있다.** 링크를 열기 전에 인스턴스를 켜야 한다.
> health 000 + SSH timeout 이면 장애가 아니라 정지 상태다.

> Prometheus 보존은 15일이다. **2026-08-15 이후에는 이 링크들이 빈 그래프가 된다.** 그때는 같은
> 디렉토리의 `timeseries-*.json` 덤프가 유일한 원자료다(그래서 함께 커밋한다).

> 그래프의 시각은 브라우저 시간대(KST)로 보인다. 이 문서와 리포트의 시각은 전부 **UTC** 다 —
> 9시간을 빼고 읽는다(`03:29` = `18:29Z`).

### 이 회차의 캡처 함정

- **A(600석)와 B(3,000석)를 한 그래프에 못 넣는다.** 회차가 연속이 아니고 사이에 재시작이
  끼어 있다. 쿼리와 축을 똑같이 맞춘 **두 장**을 나란히 놓는다(`-a` / `-b` 접미사).
  캡처할 때 브라우저 창 크기를 바꾸지 말 것 — 두 장의 가로 비율이 달라지면 비교가 왜곡된다.
- **분해 그래프의 세 선은 스케일이 극단적으로 다르다.** acquire 는 0.003-0.05ms 라 server(2-3ms)
  옆에서 바닥에 눌려 보이지 않는다. **그것이 이 회차의 결론이므로**(커넥션 획득 ≈ 0) 자동 스케일
  그대로 찍고, 눌려 있다는 사실 자체를 읽는다. 로그 축으로 바꾸지 말 것.
  **그 대가로 분해 두 장의 Y축 범위가 서로 달라진다**(실제 캡처: A 0-35 / B 0-14 — A 의 워밍업
  첫 스파이크가 축을 끌어올렸다). 두 장을 나란히 놓을 때 **선의 높이가 아니라 눈금을 읽어야 한다.**
  처리량 두 장은 축이 같다(0-180).
- **평균은 ms 로 환산해 넣었다**(`1000 *`). 범례에는 메트릭 이름 대신 **쿼리 전문**이 그대로 뜬다 —
  길어서 한눈에 안 들어오므로 쿼리 순서로 읽는다(분해 그래프는 위에서부터 server / usage / acquire).
- **80 계단 구간이 이 회차의 본론이다.** A 는 18:29:19-18:34:19Z, B 는 18:47:05-18:52:05Z 다.
  적합에 쓴 창은 그 앞뒤 30초를 자른 구간이다(`round-times.txt`).

## 캡처 목록

**4장이다.**

### graph-throughput-a.png — **600석 처리량·서버 평균.** 계단 40/80/160 이 보이고 서버 평균이 1.9-2.1ms 대에 머문다

측정 창: 2026-07-31T18:23:00Z ~ 2026-07-31T18:39:30Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28k6_http_reqs_total%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%20%2F%20sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785522180000%22%2C%22to%22%3A%221785523170000%22%7D%7D%7D

### graph-throughput-b.png — **3,000석 처리량·서버 평균.** 위와 쿼리·축이 같으니 나란히 놓고 읽는다. 같은 계단에서 서버 평균이 2.9ms 대로 올라간다(좌석당 비용)

측정 창: 2026-07-31T18:41:00Z ~ 2026-07-31T18:57:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28k6_http_reqs_total%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%20%2F%20sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785523260000%22%2C%22to%22%3A%221785524220000%22%7D%7D%7D

### graph-breakdown-a.png — **600석 구간 분해.** 위에서부터 server(전체) / usage(커넥션 사용) / acquire(커넥션 획득). **acquire 가 바닥에 붙어 보이지 않는 것이 결론이다**

측정 창: 2026-07-31T18:23:00Z ~ 2026-07-31T18:39:30Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%221000%20%2A%20sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%20%2F%20sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20sum%28rate%28hikaricp_connections_usage_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%5B1m%5D%29%29%20%2F%20sum%28rate%28hikaricp_connections_usage_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%221000%20%2A%20sum%28rate%28hikaricp_connections_acquire_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%5B1m%5D%29%29%20%2F%20sum%28rate%28hikaricp_connections_acquire_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785522180000%22%2C%22to%22%3A%221785523170000%22%7D%7D%7D

### graph-breakdown-b.png — **3,000석 구간 분해.** usage 가 server 를 따라 올라가는 반면 acquire 는 여전히 바닥이다. 좌석 수가 usage 에만 실린다는 §2-3 의 그림

측정 창: 2026-07-31T18:41:00Z ~ 2026-07-31T18:57:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%221000%20%2A%20sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%20%2F%20sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20sum%28rate%28hikaricp_connections_usage_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%5B1m%5D%29%29%20%2F%20sum%28rate%28hikaricp_connections_usage_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%221000%20%2A%20sum%28rate%28hikaricp_connections_acquire_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%5B1m%5D%29%29%20%2F%20sum%28rate%28hikaricp_connections_acquire_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785523260000%22%2C%22to%22%3A%221785524220000%22%7D%7D%7D
