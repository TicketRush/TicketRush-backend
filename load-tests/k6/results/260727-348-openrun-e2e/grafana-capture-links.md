# Grafana Explore 캡처 링크 (#348)

**SSH 터널(`-L 3000:localhost:3000`)이 떠 있어야 링크가 열린다.**

```
ssh -i <key>.pem -N -L 9090:localhost:9090 -L 3000:localhost:3000 ubuntu@54.116.243.250
```

측정 창(KST):

| 회차 | 구간 |
|---|---|
| 스모크 (캘리브레이션) | 17:39:02 ~ 17:43:01 |
| 계단식 | 23:38:24 ~ 00:05:05 (드레인 포함) |
| 오픈런 스파이크 | 00:15:59 ~ 00:38:48 (드레인 포함) · **피크 00:22:19~00:27:19** |
| 검표 스모크 | 00:41:41 ~ 00:44:59 |

각 링크는 시간 범위가 URL 에 박혀 있어 열면 바로 해당 창이 뜬다. 캡처 후 이 디렉토리에 같은 파일명으로 저장한다.

> ⚠️ 배포본 Grafana 에는 image renderer 가 없다(Alpine 이미지라 플러그인 방식 불가). `/render` 는 안내 이미지만 반환하므로 **사람이 터널로 열어 직접 캡처**해야 한다.
> Prometheus 보관은 15일(`retention.time=15d`)이라 **2026-08-11 경까지** 위 창을 조회할 수 있다.

## graph-seat-layouts-smoke.png

**이 회차의 핵심 그림 1.** 클라이언트 p95(34.97초)와 서버 평균(81.96ms)이 같은 축에 있다. 400배 차이 = 전송 대기.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%221000%20%2A%20k6_e2e_seat_layouts_duration_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%5B1m%5D%29%20%2F%20rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785141480000%22%2C%22to%22%3A%221785141840000%22%7D%7D%7D

## graph-outbox-backlog-spike.png

**이 회차의 핵심 그림 2.** 피크(15:22:19~15:27:19Z)가 끝난 뒤 86초에 backlog 정점(1,219)이 온다. VU 곡선과 겹쳐 보면 '부하는 내려갔는데 backlog는 올라간다'가 한눈에 보인다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_outbox_backlog%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22k6_vus%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785165300000%22%2C%22to%22%3A%221785166800000%22%7D%7D%7D

## graph-waiting-vs-receiving-smoke.png

TTFB(waiting) 대비 본문 수신(receiving)과 실효 대역(MB/s). 병목이 처리가 아니라 전송임을 지표 하나로 보여준다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%221000%20%2A%20k6_http_req_waiting_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20k6_http_req_receiving_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22rate%28k6_data_received_total%5B1m%5D%29%20%2F%201048576%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785141480000%22%2C%22to%22%3A%221785141840000%22%7D%7D%7D

## graph-app-idle-ramp.png

계단식에서 Tomcat busy 최대 1, Hikari pending 0인데 호스트 CPU는 순간 100%. HTTP 계층은 놀고 있었다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22hikaricp_connections_pending%7Bjob%3D%5C%22ticketrush-services%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22100%20%2A%20%281%20-%20avg%28rate%28node_cpu_seconds_total%7Bjob%3D%5C%22node%5C%22%2C%20mode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785163020000%22%2C%22to%22%3A%221785164760000%22%7D%7D%7D

## graph-seat-layouts-ramp.png

대조군. 조회 도착률을 회선에 맞춰 내리자 좌석맵 p95가 92.57ms로 수렴했다(스모크 34,970ms).

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%221000%20%2A%20k6_e2e_seat_layouts_duration_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20k6_e2e_booking_duration_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785163020000%22%2C%22to%22%3A%221785164760000%22%7D%7D%7D

## graph-circuitbreaker-entry.png

#500 이관 항목. not_permitted 0 / open 0 / slow_call_rate 0 이 유지된다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22resilience4j_circuitbreaker_not_permitted_calls_total%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22resilience4j_circuitbreaker_state%7Bstate%3D%5C%22open%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22resilience4j_circuitbreaker_slow_call_rate%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785166860000%22%2C%22to%22%3A%221785167160000%22%7D%7D%7D
