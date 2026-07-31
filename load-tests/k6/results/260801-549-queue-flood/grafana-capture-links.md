# Grafana Explore 캡처 링크 (#549 회차 B)

이 회차의 증적 그래프를 찍기 위한 Grafana Explore 링크다. 쿼리와 측정 창이 URL 에 박혀 있으므로 손으로 맞출 필요가 없다.

## 캡처 절차

Grafana 에 이미지 렌더러 플러그인이 없어 **PNG 를 서버가 만들어 주지 못한다.** 아래 링크를 열어
화면을 직접 캡처해 같은 디렉토리에 지정된 파일명으로 저장한다(#403·#504·#529 회차와 같은 관행).

1. **SSH 터널을 올린다.** Grafana(3000)·Prometheus(9090)는 `127.0.0.1` 바인딩이라
   인터넷에서 접근할 수 없다(ADR 0007, 런북 §7.1).
   ```bash
   ssh -i <key>.pem -L 3000:localhost:3000 -L 9090:localhost:9090 ubuntu@54.116.243.250
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

> Prometheus 보존은 15일이다. **2026-08-15 이후에는 이 링크들이 빈 그래프가 된다.** 그때는 같은
> 디렉토리의 `timeseries-*.json` 덤프가 유일한 원자료다(그래서 함께 커밋한다).

> 그래프의 시각은 브라우저 시간대(KST)로 보인다. 이 문서와 리포트의 시각은 전부 **UTC** 다 —
> 9시간을 빼고 읽는다(`01:01` = `16:01Z`).

### ⚠️ 이 회차의 캡처 함정 — 첫 촬영에서 실제로 겪은 것

- **스케일이 다른 두 지표를 한 패널에 넣으면 작은 쪽이 0 에 붙는다.** Grafana Explore 는 축 설정을
  URL 로 받지 못하므로, 이 문서의 링크는 **큰 쪽을 쿼리에서 나눠 같은 축에 얹었다**(`k6_vus / 500`,
  `Tcp_CurrEstab / 250`). 각 장의 주의에 환산값을 적어 두었으니 그대로 읽으면 된다.
  첫 촬영에서 예매 RPS 와 `unavailable` 이 각각 0 에 붙어 **'값이 없었다' 로 오독될 그림**이 나왔다.
- **164배 차이는 나누기로도 못 붙인다.** 상태 확인 p95 는 B-1 5.27초 · B-2 32ms 라 **회차별 두 장**으로
  쪼갰다. 눈금 숫자를 읽어서 비교한다(#529 파일이 못 박은 before/after 관행).
- **라벨 이름을 덤프에서 확인하고 쓴다.** 컨테이너 메모리의 라벨은 `name=` 이 아니라 `container=` 다 —
  첫 촬영이 `No data` 로 나왔다. `timeseries-*.json` 의 `metric` 필드가 정답지다.
- **15초 샘플러의 중앙값을 '최대' 로 쓰지 않는다.** 묶음 C 에서 두 번 틀렸다(#469 Redis 6.76 → 7.46%,
  #540 seat RSS 586 → 602 MiB). 최대값은 `timeseries-*.json` 에서 뽑는다.
- **회차가 둘이다.** B-1(무효, 15:50:15Z-15:54:26Z)과 B-2(유효, 16:01:25Z-16:22:41Z). 비교 캡처는
  두 회차를 한 창(15:48-16:25)에 담아 **가운데 골짜기를 기준으로 좌우를 나눠 읽는다.**
- **`RedisCommandTimeoutException` 을 Redis 장애로 읽지 않는다.** B-1 에서 25만 줄이 찍혔지만 Redis
  컨테이너 CPU 는 3.89% 였고 `slowlog` 는 비어 있었다. Lettuce 타임아웃은 클라이언트 쪽에서 재는 값이다.

---

## 캡처 목록 (8장) — **촬영 완료**

아래 8장은 첫 촬영이 판독 불가여서 링크를 고쳐 다시 찍은 것이다. 링크는 그대로 두어
보존 기간(2026-08-15) 안에 재현·재촬영할 수 있게 한다.

### graph-booking-rps-vs-vus-b2.png — **이 회차의 전부.** 유입(VU)이 0 → 10,000 으로 오르는데 예매 RPS 는 19.60-21.00 에 평평하다

측정 창: 2026-07-31T16:01:25Z ~ 2026-07-31T16:22:41Z (UTC)

> **`k6_vus` 를 500 으로 나눠 같은 축에 얹었다 — 그래프의 20 은 VU 10,000 이다.** 나누지 않으면 축이 0-10K 가 되어 예매선이 0 에 붙고, '평평하다' 가 아니라 '예매가 아예 없었다' 로 읽힌다(첫 촬영이 그렇게 나왔다). 두 선이 20 근처에서 만나되 **하나는 비스듬히 오르고 하나는 처음부터 평평한** 것이 이 회차의 결론이다.
>
> ⚠️ **01:09:30 의 절벽을 '무너졌다' 로 읽지 말 것.** 예매선이 0 으로 떨어지는 것은 **승급이 끝나 더 예매할 사람이 없어서**다(대기 인원 0, `graph-queue-waiting-b2.png` 와 시각이 일치한다). VU 선이 20 에 그대로 남아 있는 것은 여정을 마친 VU 가 회차 끝까지 유휴로 대기하기 때문이다(1회 여정 가드). 판정 창은 **램프 종료 ~ 승급 완료(01:06:25 ~ 01:09:45)** 이고, 그 구간이 min 19.60 / max 21.00 이다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22booking-service%3A8090%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22k6_vus%20%2F%20500%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785513685000%22%2C%22to%22%3A%221785514961000%22%7D%7D%7D

### graph-queue-waiting-b2.png — 대기 인원 단조 감소 — max **3,254** → 0 (승급 완료 t+8분)

측정 창: 2026-07-31T16:01:25Z ~ 2026-07-31T16:22:41Z (UTC)

> **최대가 3,254 명이다. 1만으로 오독하지 말 것** — '1만 명이 동시에 줄 서 있는' 조건은 재현되지 않았고(리포트 §9), 이 그래프가 바로 그 증거다. 폴링 주기는 스케일이 달라 아래 별도 장으로 뺐다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_queue_waiting%7Bjob%3D%5C%22gateway%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785513685000%22%2C%22to%22%3A%221785514961000%22%7D%7D%7D

### graph-queue-poll-interval-b2.png — 서버가 지시한 폴링 주기 T — 대기 인원을 따라 **최대 9초**까지 올랐다가 하한 3초로 수렴

측정 창: 2026-07-31T16:01:25Z ~ 2026-07-31T16:22:41Z (UTC)

> 위 대기 인원 그래프와 **시간축이 같으니 나란히 놓고 읽는다.** N=3,254 일 때 T=9 는 `ceil(3254/400)` 과 정확히 맞는다 — 산식이 실제로 그렇게 동작했다는 확인이다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_queue_poll_interval_seconds%7Bjob%3D%5C%22gateway%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785513685000%22%2C%22to%22%3A%221785514961000%22%7D%7D%7D

### graph-unavailable-compare.png — **B-1 무효의 진단.** 앞 구간(B-1)에서만 `unavailable` 과 커넥션이 함께 솟고, 뒤 구간(B-2)은 커넥션만 오르고 `unavailable` 은 0 이다

측정 창: 2026-07-31T15:48:00Z ~ 2026-07-31T16:25:00Z (UTC)

> **커넥션을 250 으로 나눠 같은 축에 얹었다 — 그래프의 66.8 은 커넥션 16,701, 19 는 4,752 다.** 나누지 않으면 축이 0-18K 가 되어 `unavailable`(0-77)이 0 에 붙는다(첫 촬영이 그렇게 나왔다).
>
> ⚠️ **이 그래프를 '커넥션이 몇 이상이면 터진다' 로 읽지 말 것.** B-2 는 커넥션 4,752(그래프 19)까지 가고도 `unavailable` 0 이다 — 커넥션 수만으로는 두 회차가 갈리지 않는다. **커넥션은 원인이자 결과다**: 폴링 수요(1,250 RPS)가 용량(약 450 RPS)을 넘어 응답이 밀리면, 프록시 요청 1건이 2슬롯을 먹으므로 쌓인 요청이 그대로 커넥션 수가 된다(리틀의 법칙으로 약 13,200). 두 회차를 가른 것은 커넥션이 아니라 **`R`(1,400 → 400)** 이고, 이 그래프는 그 결과를 보여 준다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28ticketrush_queue_admission_total%7Bjob%3D%5C%22gateway%5C%22%2C%20result%3D%5C%22unavailable%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22node_netstat_Tcp_CurrEstab%7Bjob%3D%5C%22node%5C%22%7D%20%2F%20250%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785512880000%22%2C%22to%22%3A%221785515100000%22%7D%7D%7D

### graph-status-latency-b1.png — **무효 회차의 지연.** 상태 확인 p95 가 **5.27초**까지 간다(정상은 30ms 대)

측정 창: 2026-07-31T15:50:15Z ~ 2026-07-31T15:54:26Z (UTC)

> 아래 B-2 장과 **쿼리가 같고 창만 다르다.** 한 그래프에 못 넣는 이유는 164배 차이라 B-2 가 바닥에 뭉개져 '0 이었다' 처럼 보이기 때문이다(#529 파일이 못 박은 before/after 관행). **두 장의 세로 눈금 값을 읽어서 비교한다** — 브라우저 창 크기는 두 장 사이에 바꾸지 말 것.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22k6_queue_status_duration_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785513015000%22%2C%22to%22%3A%221785513266000%22%7D%7D%7D

### graph-status-latency-b2.png — **유효 회차의 지연.** 같은 쿼리로 p95 **32.1ms** — 위 B-1 의 5.27초와 164배 차이다

측정 창: 2026-07-31T16:01:25Z ~ 2026-07-31T16:22:41Z (UTC)

> 축 최대값이 0.03 대일 것이다. 위 장의 축 최대값(5 대)과 **눈금 숫자를 비교**한다. 선 모양만 보면 둘 다 '평평' 해 보이므로 반드시 눈금을 읽는다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22k6_queue_status_duration_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785513685000%22%2C%22to%22%3A%221785514961000%22%7D%7D%7D

### graph-wait-to-admit-b2.png — 사용자 체감 — 진입부터 입장까지 p95 **154.6초**(avg 86.9 / max 166.6)

측정 창: 2026-07-31T16:01:25Z ~ 2026-07-31T16:22:41Z (UTC)

> 고원에 도달한 뒤 평평한 것은 p95 가 누적 분포라서다. **이 값은 '얼마나 기다렸나' 이지 '얼마나 느렸나' 가 아니다** — 대기열은 기다리게 하는 것이 목적이므로 크다고 나쁜 것이 아니다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22k6_queue_wait_to_admit_seconds_p95%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785513685000%22%2C%22to%22%3A%221785514961000%22%7D%7D%7D

### graph-gateway-memory-b2.png — `mem_limit` 512m(536,870,912 B) 대비 여유. reactive Lettuce/netty 가 올라간 뒤 1만 VU 첫 부하다

측정 창: 2026-07-31T16:01:25Z ~ 2026-07-31T16:22:41Z (UTC)

> ⚠️ **첫 촬영은 `No data` 였다** — 라벨을 `name=` 으로 썼는데 실제 라벨은 `container=` 다. 이 링크는 고친 것이다. 바이트 단위라 눈금이 크니 512m 선(536,870,912)을 기준으로 읽는다.

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22ticketrush_container_memory_usage_bytes%7Bcontainer%3D%5C%22gateway-service%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785513685000%22%2C%22to%22%3A%221785514961000%22%7D%7D%7D

---

## 캡처 목록 (3장) — 첫 촬영으로 충분했다

이 3장은 스케일이 섞이지 않아 처음부터 판독 가능했다. 링크는 위 촬영분과 같은 규칙으로
만들어져 있으나, 다시 찍을 일이 없어 본문에는 판독 지침만 남긴다.

### graph-queue-admission-b2.png — 승급률과 `unavailable` 이 0 인 것. waiting ≈365, admitted ≈20 평평, unavailable 0

> 세 시리즈가 모두 읽히고 `unavailable` 이 범례에 이름째 보인다. **admitted 가 20 에 평평한 것**이 완료 조건의 또 다른 표현이다.

### graph-host-cpu-compare.png — B-1 max **99.20%**(avg 68.01) vs B-2 max **79.53%**(avg 35.27)

> 앞 고원이 B-1, 뒤 구간이 B-2, 마지막 5% 평지가 회차 종료 후 유휴다.

### graph-redis-memory-b2.png — 무효 기준 감시축. max **13.86%** (기준 75% = 48MB/64MB)

> `noeviction` 이라 상한에 닿으면 대기열이 아니라 **좌석 락 SET 이 거절된다**(ADR 0008).

