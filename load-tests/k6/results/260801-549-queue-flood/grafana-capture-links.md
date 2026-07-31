# Grafana 캡처 링크 — 260801-549-queue-flood

Grafana 렌더러 플러그인이 없어 **수동 캡처**한다. 아래 링크는 datasource·쿼리·UTC 절대 시각이 URL에 박혀 있어 그대로 열면 같은 화면이 재현된다.

터널이 필요하다: `ssh -L 3000:localhost:3000 -L 9090:localhost:9090 -i <pem> ubuntu@54.116.243.250`

- Grafana: `http://localhost:3000` — **먼저 로그인한 뒤** 아래 링크를 연다(로그인 전에 열면 리다이렉트로 시간 범위가 날아간다)
- **회차 B-2(유효)**: `from=1785513685000&to=1785514961000` (`2026-07-31T16:01:25Z ~ 16:22:41Z`)
- **회차 B-1(무효)**: `from=1785513015000&to=1785513266000` (`2026-07-31T15:50:15Z ~ 15:54:26Z`)

> **캡처 원칙**: 15초 샘플러가 훑은 구간의 **중앙값을 "최대"로 쓰지 않는다.** 묶음 C에서 두 번 틀렸다(#469 Redis 6.76 → 7.46%, #540 seat RSS 586 → 602 MiB). 최대값은 시계열 덤프(`timeseries-*.json`)에서 뽑거나 이 캡처로 확인한다.

---

## 필수 캡처

### A. 완료 조건 (B-2)

| # | 저장 파일명 | 무엇 | PromQL |
|---|---|---|---|
| 1 | `graph-booking-rps-vs-vus-b2.png` | **이 회차의 전부.** 유입이 오르는데 예매 RPS가 평평한가 | `sum(rate(http_server_requests_seconds_count{instance="booking-service:8090"}[1m]))` 와 `k6_vus` 를 **한 패널에 두 축으로** |
| 2 | `graph-queue-waiting-b2.png` | 대기 인원 단조 감소 (max 3,254 → 0) | `ticketrush_queue_waiting{job="gateway"}` |
| 3 | `graph-queue-admission-b2.png` | 승급률과 `unavailable`이 0인 것 | `sum by (result) (rate(ticketrush_queue_admission_total{job="gateway"}[1m]))` |
| 4 | `graph-queue-poll-interval-b2.png` | 서버가 지시한 T가 대기 인원을 따라 움직이는가 (max 9초) | `ticketrush_queue_poll_interval_seconds{job="gateway"}` |

**1번은 두 축의 스케일이 관건이다.** 예매 RPS는 0-30, VU는 0-10,000이라 한 축에 그리면 예매 선이 바닥에 붙어 "평평함"이 안 보인다. 우측 축(VU)을 별도로 잡고, **좌측 축 범위를 0-40으로 고정**해 20 근처의 평탄함이 드러나게 한다.

### B. 무효 판정의 근거 (B-1 vs B-2 비교)

| # | 저장 파일명 | 무엇 | PromQL |
|---|---|---|---|
| 5 | `graph-unavailable-compare.png` | **B-1이 왜 무효인가.** 두 회차를 같은 척도로 | `sum(rate(ticketrush_queue_admission_total{result="unavailable"}[1m]))` — 시간 범위를 각각 잡아 **두 장** 또는 15:48-16:25 한 장에 둘 다 |
| 6 | `graph-host-cpu-compare.png` | B-1 99.20% vs B-2 79.53% | `100 * (1 - avg(rate(node_cpu_seconds_total{job="node", mode="idle"}[1m])))` |
| 7 | `graph-tcp-established-b1.png` | **B-1 무효의 원인축.** 커넥션 16,701 | `node_netstat_Tcp_CurrEstab{job="node"}` |
| 8 | `graph-queue-status-latency-compare.png` | B-1 p95 5.27s vs B-2 32.1ms | `k6_queue_status_duration_p95` |

**5·6·8은 15:48-16:25를 한 장에 담는 편이 낫다** — 두 회차가 한 화면에 나란히 보여야 "설정 하나 바꿔 이렇게 달라졌다"가 그림으로 읽힌다. 다만 8번은 스케일이 5.27s와 32ms라 **로그 축**으로 두어야 B-2가 바닥에 뭉개지지 않는다.

### C. 무효 기준 감시축 (B-2)

| # | 저장 파일명 | 무엇 | PromQL |
|---|---|---|---|
| 9 | `graph-redis-memory-b2.png` | max 13.86% (기준 75%) | `100 * redis_memory_used_bytes / redis_memory_max_bytes` |
| 10 | `graph-gateway-memory-b2.png` | `mem_limit` 512m 대비 여유 | `ticketrush_container_memory_usage_bytes{name="gateway-service"}` |

---

## 판독 시 주의

- **`queue_status_unavailable`이 0인지 먼저 본다.** >0이면 fail-closed(ADR 0008)가 발동한 회차라 그래프가 아무리 깔끔해도 폐기한다. B-1이 그래서 폐기됐다.
- **`RedisCommandTimeoutException`을 Redis 장애로 읽지 않는다.** B-1에서 25만 줄이 찍혔지만 **Redis 컨테이너 CPU는 3.89%였고 `slowlog`는 비어 있었다.** Lettuce 타임아웃은 클라이언트 쪽에서 재는 값이라, 게이트웨이가 CPU를 못 잡으면 서버가 즉답해도 터진다. 캡처할 때 Redis 축과 게이트웨이 축을 함께 두어야 이 오해가 생기지 않는다.
- **게이트웨이 `http_server_requests`의 `uri` 라벨은 못 쓴다.** `/**`·`UNKNOWN`으로 뭉개진다(#402 실측 카디널리티 4). 경로별 구분은 `ticketrush_queue_*` 커스텀 지표로만 가능하다.
- **대기 인원 최대가 3,254명이라는 것을 그래프에서 확인한다.** "1만 명이 동시에 줄 서 있는" 조건은 재현되지 않았고(리포트 §9), 그래프를 1만으로 오독하기 쉽다.
- **Prometheus 보존 기간은 15일이다.** 그 뒤에 이 링크를 열면 빈 그래프가 나온다 — 원자료는 `timeseries-*.json`이다. 캡처를 마치면 PR의 `📸 스크린샷` 절을 갱신한다.
