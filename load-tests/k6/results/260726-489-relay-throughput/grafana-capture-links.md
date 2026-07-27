# Grafana Explore 캡처 링크 (#489)

측정 창: 2026-07-26 23:45 ~ 23:56 KST (= 14:45 ~ 14:56 UTC)
SSH 터널이 떠 있어야 한다 — 관측 스택은 `127.0.0.1` 바인딩이다(ADR 0007).

```bash
ssh -i "<key>.pem" -L 3000:localhost:3000 -L 9090:localhost:9090 ubuntu@54.116.243.250
```

링크를 열면 그 시간 범위·쿼리가 이미 채워진 Explore 화면이 뜬다. 바로 캡처해 이 디렉토리에 저장한다.

> **라벨 주의.** 이 스택의 앱 메트릭 라벨은 `instance="<서비스>:8090"` 이고 `job="ticketrush-services"` 다. `application` 라벨은 **존재하지 않는다** — 런북 §11.6 이 그렇게 적고 있었고, 그대로 쓴 첫 캡처가 No data 로 나왔다. PromQL 은 없는 라벨에 대해 에러가 아니라 빈 결과를 주므로 조용히 실패한다.

## graph-outbox-backlog-inflight.png

outbox 적체 + in-flight (핵심 지표). backlog 가 tick 마다 0 으로 떨어지는지 본다.

⚠️ **in-flight 는 이 그래프로 피크를 읽지 않는다.** Prometheus 스크랩이 15초라 배치가 나가고 콜백이 돌아오는 수 초짜리 구간을 통째로 놓친다 — 실제 피크(seat 284 / booking 296)는 5초 폴링 CSV(`outbox-b1.csv`)에서 나온 값이다. 여기서는 "바닥에 붙어 정체하지 않는다" 정도만 읽는다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_outbox_backlog%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22ticketrush_outbox_in_flight%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785077100000%22%2C%22to%22%3A%221785077760000%22%7D%7D%7D

## graph-relay-rate.png

릴레이 발행률 — 서비스별로 나눠 본다. `sum by (instance)` 다.

⚠️ 라벨을 빼거나 `sum by (application)` 으로 묶으면 두 서비스가 하나로 합쳐져 **합계(약 90/s)** 가 그려진다. seat·booking 각각이 생성률 33.3/s 를 넘는지가 판정 기준이므로 반드시 나눠서 본다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%20by%20%28instance%29%20%28rate%28ticketrush_outbox_relay_total%7Bresult%3D%5C%22success%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785077100000%22%2C%22to%22%3A%221785077760000%22%7D%7D%7D

## graph-seat-expired-backlog.png

좌석 만료 적체 소진 곡선 — tick 당 2,000건씩 5 tick. #345 에서는 게이지 미배포로 못 찍었던 그래프다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_seat_hold_expired_backlog%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785077100000%22%2C%22to%22%3A%221785077760000%22%7D%7D%7D

## graph-hikari.png

커넥션 풀 압박 — 배치를 3배로 키운 뒤에도 pending 0 인지.

⚠️ 라벨은 `instance` 다. `application` 라벨은 이 스택에 존재하지 않아 쓰면 조용히 No data 가 뜬다(#489에서 실제로 겪었다).

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22hikaricp_connections_pending%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22hikaricp_connections_active%7Binstance%3D%5C%22seat-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785077100000%22%2C%22to%22%3A%221785077760000%22%7D%7D%7D
