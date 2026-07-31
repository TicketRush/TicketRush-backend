# Grafana Explore 캡처 링크 (#539 — 좌석맵 JSON 캐싱 전/후)

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

> ⚠️ **부하 측정 중에는 Grafana 를 열지 않는다.** 패널마다 주기적으로 쿼리가 나가 측정 대상의
> CPU 를 소모한다(ADR 0004). 데이터는 TSDB 에 남으므로 회차가 끝난 뒤에 열어 본다.

> Prometheus 보존은 15일이다. **2026-08-15 이후에는 이 링크들이 빈 그래프가 된다.** 그때는 같은
> 디렉토리의 `timeseries-*.json` 덤프가 유일한 원자료다(그래서 함께 커밋한다).

> 그래프의 시각은 브라우저 시간대(KST)로 보인다. 이 문서와 리포트의 시각은 전부 **UTC** 다 —
> 9시간을 빼고 읽는다(`14:49` = `05:49Z`).

### 이 회차의 캡처 함정

- **before 가 회차 둘로 나뉘어 있다.** 1차(20,40,60)가 미포화로 끝나 2차(60,80,100)를 이어
  돌렸다. **60 계단이 겹치므로 두 장을 나란히 놓고 그 계단의 높이가 맞는지 눈으로 검산한다**
  (실측 90.51% vs 90.03%).
- **2차 회차의 앞 3분은 콜드 JIT 구간이다.** CPU 가 99.6% 에서 시작해 90.0% 로 내려온다.
  이 구간을 포화로 읽으면 안 된다 — 같은 처리량에 CPU 만 더 드는 워밍업이고, 표에는 넣지 않았다.
- **before/after 는 한 그래프에 못 넣는다.** 시간 창이 다르다. 쿼리와 축을 똑같이 맞춘 두 장을
  만들어 나란히 놓는다. 캡처할 때 **브라우저 창 크기를 바꾸지 말 것** — 가로 비율이 달라지면
  비교가 왜곡된다.
- **서버 평균은 ms 로 환산해 넣었다**(`1000 *`). 그 연산이 메트릭 이름을 지우므로 범례가
  비어 보인다 — 값 순서로 읽는다(rps 가 위, ms 가 아래).
- **`dropped_iterations` 는 누적 카운터다.** 계단마다 얼마나 늘었는지를 기울기로 읽는다.
  2차 회차 시작 1분의 124 건은 콜드 워밍업분이라 포화 신호가 아니다.

## 캡처 목록 — before (배포 전, `342a8ef5`)

### graph-before-ramp1-throughput.png — **1차(20,40,60).** 파란 선이 seat-layouts 서버 처리량(rps), 초록이 서버 평균(ms). 세 계단 모두 도착률을 100% 소화하고 평균이 9.6 → 28.4 → 96.2ms 로 오른다

측정 창: 2026-07-31T05:49:00Z ~ 2026-07-31T06:06:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-layouts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-layouts%5C%22%7D%5B1m%5D%29%29%20%2F%20sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-layouts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785476940000%22%2C%22to%22%3A%221785477960000%22%7D%7D%7D

### graph-before-ramp1-saturation.png — **1차 포화 축.** CPU 40.8 → 73.0 → 90.5%, `dropped` 는 전 구간 0 이다. 90% 를 찍고도 손실이 없는 것이 '무릎 직전' 의 모습이다

측정 창: 2026-07-31T05:49:00Z ~ 2026-07-31T06:06:00Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20%281%20-%20avg%28rate%28node_cpu_seconds_total%7Bjob%3D%5C%22node%5C%22%2C%20mode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22k6_dropped_iterations_total%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785476940000%22%2C%22to%22%3A%221785477960000%22%7D%7D%7D

### graph-before-ramp2-throughput.png — **2차(60,80,100) — 이 회차의 핵심 그림.** 80 계단부터 처리량이 도착률을 못 따라가고(60.0 → 66.9 → 75.7 rps) 평균이 96 → 741ms 로 꺾인다

측정 창: 2026-07-31T06:08:00Z ~ 2026-07-31T06:25:30Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-layouts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-layouts%5C%22%7D%5B1m%5D%29%29%20%2F%20sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-layouts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785478080000%22%2C%22to%22%3A%221785479130000%22%7D%7D%7D

### graph-before-ramp2-saturation.png — **2차 포화 축.** CPU 가 99.7% 에 붙고 `dropped` 기울기가 80 계단부터 급해진다. 앞 3분의 콜드 구간(99.6% → 90.0%)과 구분해 읽는다

측정 창: 2026-07-31T06:08:00Z ~ 2026-07-31T06:25:30Z (UTC)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20%281%20-%20avg%28rate%28node_cpu_seconds_total%7Bjob%3D%5C%22node%5C%22%2C%20mode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22k6_dropped_iterations_total%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785478080000%22%2C%22to%22%3A%221785479130000%22%7D%7D%7D

## 캡처 목록 — after (배포 후, `e0b42dc6`)

측정 창은 4장 모두 2026-07-31T07:19:30Z ~ 2026-07-31T07:47:00Z (UTC) 다.

### graph-after-throughput.png — **after 처리량 + 서버 평균.** before 2차와 쿼리·축이 같으니 나란히 놓는다. 100 계단까지 처리량이 도착률을 그대로 따라가고, 평균이 80 계단에서 10.1ms 다(before 741ms)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-layouts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%221000%20%2A%20sum%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-layouts%5C%22%7D%5B1m%5D%29%29%20%2F%20sum%28rate%28http_server_requests_seconds_count%7Binstance%3D%5C%22seat-service%3A8090%5C%22%2C%20uri%3D~%5C%22.%2Aseat-layouts%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785482370000%22%2C%22to%22%3A%221785484020000%22%7D%7D%7D

### graph-after-saturation.png — **after 포화 축.** CPU 가 35 → 98% 로 오르지만 `dropped` 는 100 계단에 가서야 132 건이다. before 는 80 계단에서 이미 853 건이었다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20%281%20-%20avg%28rate%28node_cpu_seconds_total%7Bjob%3D%5C%22node%5C%22%2C%20mode%3D%5C%22idle%5C%22%7D%5B1m%5D%29%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22k6_dropped_iterations_total%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785482370000%22%2C%22to%22%3A%221785484020000%22%7D%7D%7D

### graph-after-cache-hitrate.png — **캐시 히트율.** hit/miss/failure 세 선. `failure` 가 바닥에 붙어 있는 것(전 구간 0)이 Redis 예산 안전의 증적이다. 60 계단부터 miss 가 거의 사라진다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%20by%20%28result%29%20%28rate%28ticketrush_seat_seatmap_cache_total%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785482370000%22%2C%22to%22%3A%221785484020000%22%7D%7D%7D

### graph-after-redis-budget.png — **Redis 예산.** 최대 6.76% 로 `maxmemory 64mb` 대비 여유 14배. 사용률과 캐시 카운터를 겹쳐 둔 것은 `used` 만 보면 좌석 락 SET 거절 위험이 안 보이기 때문이다(ADR 0008)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22100%20%2A%20redis_memory_used_bytes%20%2F%20redis_memory_max_bytes%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22sum%20by%20%28result%29%20%28rate%28ticketrush_seat_seatmap_cache_total%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785482370000%22%2C%22to%22%3A%221785484020000%22%7D%7D%7D

