# Grafana 캡처 링크 — 260731-546-queue-status

Grafana 렌더러 플러그인이 없어 **수동 캡처**한다. 아래 링크는 datasource·쿼리·UTC 절대 시각이 URL에 박혀 있어 그대로 열면 같은 화면이 재현된다.

터널이 필요하다: `ssh -L 3000:localhost:3000 -L 9090:localhost:9090 -i <pem> ubuntu@54.116.243.250`

- Grafana: `http://localhost:3000`
- 시간 범위(A-1): `from=<A1_START_MS>&to=<A1_END_MS>` — `metadata.txt` 의 `A1_START_UTC`/`A1_END_UTC` 를 ms 로 환산해 채운다
- 시간 범위(A-2): `from=<A2_START_MS>&to=<A2_END_MS>`

> **캡처 원칙**: 15초 샘플러가 훑은 구간의 **중앙값을 "최대" 로 쓰지 않는다.** 묶음 C 에서 두 번 틀렸다(#469 Redis 6.76→7.46%, #540 seat RSS 586→602 MiB). 최대값은 시계열 덤프(`timeseries-*.json`)에서 뽑거나 이 캡처로 확인한다.

---

## 필수 캡처 (A-1 · A-2 각각)

| # | 저장 파일명 | 무엇 | PromQL |
|---|---|---|---|
| 1 | `graph-host-cpu-a1.png` / `-a2.png` | **A/B 1순위 판정축.** 호스트 CPU | `100 * (1 - avg(rate(node_cpu_seconds_total{job="node", mode="idle"}[1m])))` |
| 2 | `graph-queue-admission-rps-a1.png` / `-a2.png` | 상태 확인 RPS(= 폴링 처리량). 계단이 실제로 올라갔는지 | `sum by (result) (rate(ticketrush_queue_admission_total{job="gateway"}[1m]))` |
| 3 | `graph-k6-rps-vs-dropped-a1.png` / `-a2.png` | **무릎 판정축.** 도착률 대비 실제 RPS + dropped | `sum(rate(k6_http_reqs_total[1m]))` 와 `k6_dropped_iterations_total` 를 한 패널에 |
| 4 | `graph-gateway-memory-a1.png` / `-a2.png` | **A/B 2순위 판정축.** reactive Lettuce/netty 추가 후 첫 부하 | `ticketrush_container_memory_usage_bytes{name="gateway-service"}` |
| 5 | `graph-k6-connecting-tls-a1.png` / `-a2.png` | **A/B 3순위 판정축.** 재핸드셰이크 비용 | `k6_http_req_connecting_p95` 와 `k6_http_req_tls_handshaking_p95` |
| 6 | `graph-redis-memory-a1.png` / `-a2.png` | 무효 판정(≥48MB). `noeviction` 상한에 닿으면 좌석 락 SET 까지 거절된다 | `redis_memory_used_bytes` |
| 7 | `graph-queue-status-latency-a1.png` / `-a2.png` | 경로 지연. 서버 축과 k6 축의 차이가 곧 네트워크 왕복 | `k6_queue_status_duration_p95` 와 `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="gateway"}[5m])))` |

## 선택 캡처

| 저장 파일명 | 무엇 | PromQL |
|---|---|---|
| `graph-host-memory.png` | 호스트 여유(컨테이너 합 7,264 MiB + nginx 워커) | `node_memory_MemTotal_bytes{job="node"} - node_memory_MemAvailable_bytes{job="node"}` |
| `graph-queue-waiting.png` | 대기 인원 게이지가 PRELOAD 규모를 반영하는지 | `ticketrush_queue_waiting{job="gateway"}` |
| `graph-net-tx.png` | 회선. 응답이 작아(≤203 B) 여기서 막힐 일은 없어야 한다 | `rate(node_network_transmit_bytes_total{job="node", device="ens5"}[1m])` |

---

## 판독 시 주의

- **`queue_status_admitted_leak` 이 0 인지 먼저 본다.** >0 이면 폴링 대상이 승급해 측정 경로가 `GET`+`ZRANK` 2회에서 3회(+`SET`)로 바뀐 것이라 이 회차는 `R` 을 재지 못했다. 그래프가 아무리 깔끔해도 폐기한다.
- **게이트웨이 `http_server_requests` 의 `uri` 라벨은 못 쓴다.** `/**`·`UNKNOWN` 으로 뭉개진다(#402 실측 카디널리티 4). 경로별 구분은 `ticketrush_queue_*` 커스텀 지표로만 가능하다.
- **A/B 두 회차의 절대값을 이전 회차(#348·#403·#529)와 직접 잇지 않는다.** 이번엔 nginx `worker_connections` 가 768 → 16384 로 바뀌었다(§G1). 같은 호스트라도 전제가 다르다.
