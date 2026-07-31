# Grafana Explore 캡처 링크 (#529)

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

- **before/after 는 한 그래프에 못 넣는다.** 시간 창이 다르기 때문이다. 대신 **쿼리와 축을
  똑같이 맞춘 두 장**을 만들어 나란히 놓는다(`-pre` / `-post` 접미사). 캡처할 때 브라우저 창
  크기를 바꾸지 말 것 — 두 장의 가로 비율이 달라지면 비교가 왜곡된다.
- **서버 평균은 ms 로 환산해 넣었다**(`1000 *`). 그 연산이 메트릭 이름을 지우므로 범례가
  비어 보인다 — 값 순서로 읽는다(rps 가 위, ms 가 아래).
- **A-pre 3,000석의 240 계단은 122.61ms, A-post 는 3.59ms 다.** 두 장을 같은 세로 눈금으로
  맞추면 A-post 가 바닥에 눌려 아무것도 안 보인다. **자동 스케일 그대로 찍고 눈금 값을 읽는다.**

## 캡처 목록

**6장이다.**

### graph-counts-b-pre.png — **3,000석 A-pre(인덱스 없음).** 파란 선이 처리량, 초록이 서버 평균(ms). 240 계단에서 122.61ms 로 꺾이고 320 부터 243.92 rps 고원

측정 창: 2026-07-30T09:12:00Z ~ 2026-07-30T09:44:30Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28k6_http_reqs_total%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%20%2F%20sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785402720000%22%2C%22to%22%3A%221785404670000%22%7D%7D%7D

### graph-counts-b-post.png — **3,000석 A-post(커버링).** 위와 쿼리·축이 같으니 나란히 놓고 읽는다. 240 계단이 3.59ms 로 평평하고 고원이 396.75 rps 로 밀렸다

측정 창: 2026-07-30T11:12:00Z ~ 2026-07-30T11:44:30Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28k6_http_reqs_total%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%20%2F%20sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785409920000%22%2C%22to%22%3A%221785411870000%22%7D%7D%7D

### graph-counts-b-pre-saturation.png — A-pre 포화 판정 3축. **240 계단에서 이미 CPU 97.91% · tomcat busy 26 · hikari pending 17.69** 가 함께 붙는다

측정 창: 2026-07-30T09:12:00Z ~ 2026-07-30T09:44:30Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20%281%20-%20avg%28rate%28node_cpu_seconds_total%7Bjob%3D%5C%22node%5C%22%2C%20mode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22hikaricp_connections_pending%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785402720000%22%2C%22to%22%3A%221785404670000%22%7D%7D%7D

### graph-counts-b-post-saturation.png — A-post 같은 3축. **같은 240 계단이 CPU 53.24% · busy 2.19 · pending 0.31** 이다. 세 축이 붙는 지점이 480 으로 밀렸다

측정 창: 2026-07-30T11:12:00Z ~ 2026-07-30T11:44:30Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20%281%20-%20avg%28rate%28node_cpu_seconds_total%7Bjob%3D%5C%22node%5C%22%2C%20mode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22hikaricp_connections_pending%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785409920000%22%2C%22to%22%3A%221785411870000%22%7D%7D%7D

### graph-counts-a-pre.png — 600석 A-pre. 480 계단에서 34.40ms 로 들리기 시작한다

측정 창: 2026-07-30T08:36:00Z ~ 2026-07-30T09:08:30Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28k6_http_reqs_total%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%20%2F%20sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785400560000%22%2C%22to%22%3A%221785402510000%22%7D%7D%7D

### graph-counts-a-post.png — 600석 A-post. 같은 480 계단이 5.36ms 다(−84%)

측정 창: 2026-07-30T10:39:30Z ~ 2026-07-30T11:11:30Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28k6_http_reqs_total%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%20%2F%20sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-counts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785407970000%22%2C%22to%22%3A%221785409890000%22%7D%7D%7D

