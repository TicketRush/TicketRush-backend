# Grafana Explore 캡처 링크 (#489)

측정 창: 2026-07-26 23:45 ~ 23:56 KST (= 14:45 ~ 14:56 UTC)
SSH 터널이 떠 있어야 한다 — 관측 스택은 `127.0.0.1` 바인딩이다(ADR 0007).

```bash
ssh -i "<key>.pem" -L 3000:localhost:3000 -L 9090:localhost:9090 ubuntu@54.116.243.250
```

링크를 열면 그 시간 범위·쿼리가 이미 채워진 Explore 화면이 뜬다. 바로 캡처해 이 디렉토리에 저장한다.

## graph-outbox-backlog-inflight.png

outbox 적체 + in-flight (핵심 지표) — backlog 가 tick 마다 0 으로 떨어지는지, in_flight 가 300 에 붙어 정체하지 않는지

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_outbox_backlog%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22ticketrush_outbox_in_flight%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785077100000%22%2C%22to%22%3A%221785077760000%22%7D%7D%7D

## graph-relay-rate.png

릴레이 발행률 — 20/s(이전 상한) 를 넘고 생성률 33.3/s 위에 있는지

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%20by%20%28application%29%20%28rate%28ticketrush_outbox_relay_total%7Bresult%3D%5C%22success%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785077100000%22%2C%22to%22%3A%221785077760000%22%7D%7D%7D

## graph-seat-expired-backlog.png

좌석 만료 적체 소진 곡선 — tick 당 2,000건씩 5 tick

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_seat_hold_expired_backlog%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785077100000%22%2C%22to%22%3A%221785077760000%22%7D%7D%7D

## graph-hikari.png

커넥션 풀 압박 — 배치 3배 후에도 pending 0 인지

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22hikaricp_connections_pending%7Bapplication%3D%5C%22seat-service%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22hikaricp_connections_active%7Bapplication%3D%5C%22seat-service%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785077100000%22%2C%22to%22%3A%221785077760000%22%7D%7D%7D
