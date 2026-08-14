# Grafana 캡처 — #555 admit-rate 무릎 계단 회차

**캡처 완료(2026-08-14).** 아래 5장이 이 디렉터리에 있다. 다시 찍을 일이 있으면 이 문서의 절차를 그대로 쓴다.

| 파일 | arm | admit | 구간(UTC) | 호스트 CPU max |
|---|---|---|---|---|
| [`graph-host-cpu-b12.jpg`](graph-host-cpu-b12.jpg) | `b12` | 12 | 11:35:19 ~ 11:45:29 | 66.04% |
| [`graph-host-cpu-b16.jpg`](graph-host-cpu-b16.jpg) | `b16` | 16 | 12:19:16 ~ 12:29:24 | 90.53% |
| [`graph-host-cpu-b20.jpg`](graph-host-cpu-b20.jpg) | `b20` | 20 | 11:58:25 ~ 12:08:33 | 95.91% |
| [`graph-host-cpu-a16.jpg`](graph-host-cpu-a16.jpg) | `a16` | 16 | 10:41:05 ~ 10:51:08 | 98.39% — **SSE 대조 before** |
| [`graph-host-cpu-a16-nosse.jpg`](graph-host-cpu-a16-nosse.jpg) | `a16-nosse` | 16 | 11:05:06 ~ 11:12:02 | 73.22% — **SSE 대조 after** |

**축을 눈으로 비교할 수 있게 만든 장치**: 쿼리에 `vector(100)`을 한 줄 얹었다(노란 수평선). Grafana가 축을 자동으로 잡으면 `a16`(98%)과 `a16-nosse`(73%) 두 장이 **모두 화면을 가득 채워 차이가 사라진다** — 결론의 유일한 시각 증거가 무의미해지는 지점이다. 이 상수 계열이 다섯 장의 Y축을 전부 0-100%로 못 박으므로 **다섯 장을 나란히 놓고 그대로 비교할 수 있다.** UI에서 Soft max를 만지는 것보다 재현이 쉽고 URL에 함께 저장된다.

## 다시 찍는 절차

**0. 터널 + 로그인**

```bash
ssh -N -L 3000:localhost:3000 -i ~/.ssh/ticketrush-key.pem ubuntu@54.116.243.250
```

`http://localhost:3000`에서 **먼저 로그인한다**(계정은 EC2 `~/ticketrush/deploy/.env`의 `GRAFANA_ADMIN_*`). 로그인 전에 Explore 링크를 열면 로그인 페이지로 튕기면서 URL의 쿼리·시간 범위가 날아간다.

**1. 계단별 Explore 링크**는 arm마다 따로 있다 — [`grafana-links-b12.md`](grafana-links-b12.md) · [`grafana-links-b16.md`](grafana-links-b16.md) · [`grafana-links-b20.md`](grafana-links-b20.md) · [`grafana-links-a16.md`](grafana-links-a16.md) · [`grafana-links-a16-nosse.md`](grafana-links-a16-nosse.md). 쿼리·시간 범위가 URL에 박혀 있다.

> ⚠️ 그 링크 파일들의 호스트 CPU 패널에는 `vector(100)`이 없다(`grafana-links.py`가 만든 원본이다). 위 다섯 장과 같은 축 고정을 원하면 쿼리를 하나 추가한다.

**2. 화면 정리** — 쿼리 편집기와 내비게이션이 화면을 먹는다. 브라우저 콘솔에서:

```js
document.documentElement.style.zoom = '0.62';
const s = document.createElement('style'); document.head.appendChild(s);
s.textContent = `[data-testid="data-testid Nav toolbar"],[data-testid="data-testid tooltip"],
  [data-testid="query-editor-rows"],[data-testid="data-testid query-history-button"]{display:none!important}
  header,nav[aria-label],[role="banner"]{display:none!important}`;
const ab = [...document.querySelectorAll('button')].find(b => /Add query/.test(b.textContent||''));
if (ab) ab.parentElement.parentElement.style.display = 'none';
const p = document.querySelector('[data-testid="data-testid panel content"]');
const sec = p.closest('section') || p.parentElement;
sec.style.height = '430px'; sec.style.minHeight = '430px'; sec.style.marginTop = '0';
window.dispatchEvent(new Event('resize'));
```

`zoom 0.62`가 핵심이다. 이 환경은 `devicePixelRatio = 2`라 CSS 뷰포트가 784×367밖에 안 되고, 그대로 두면 **그래프 아래쪽(0-40% 구간)이 잘린다.** 그리고 **다섯 장 모두 같은 값으로 돌려야** 기하가 맞아 비교가 성립한다.

**3. 캡처** — 창 크기를 중간에 바꾸지 않는다. 시간축 눈금 간격이 달라져 나란히 비교가 어려워진다.

## 이 회차 고유의 함정

**① 범례에 arm 이름이 없다.** 링크가 시간 범위로만 arm을 가르므로 그래프 안에는 식별자가 없다 — **파일명이 유일한 식별자다.** 한 arm을 다 찍고 다음으로 넘어간다.

**② 좌석맵 성공률은 Grafana에 없다.** `queue_seatmap_ok`는 k6 커스텀 지표이고 시리즈 A에서 remote-write가 끊겼다(리포트 §6 `LIMIT_K6_TIMESERIES`). **k6 요약 텍스트(`k6-summary-*.txt`)가 유일한 출처**이므로 리포트 §3.2의 표를 쓴다.

**③ seat-service 자원 지표는 그래프로 찍지 않았다.** `hikaricp_connections_pending` · `tomcat_threads_busy_threads`는 **포화 구간에서 스크랩 자체가 끊겨 시계열에 구멍이 난다** — 실제로 찍어 보니 점 몇 개만 남아 그래프가 오히려 오해를 부른다. 그 축은 `arm-stats-*.txt`의 min/avg/max 표가 정확하다(구멍은 그 자체로 포화 신호이므로 리포트 §3.2에 서술로 남겼다).

**④ 판정창을 눈으로 찾는 법.** 판정은 `t+150s ~ t+310s`로 했다. 유입이 `t+150s`에 끝나므로 **곡선이 꺾이는 자리 다음의 2분 40초**가 판정창이다.

**⑤ Prometheus 보존 기한 15일** — 2026-08-29 이후에는 링크가 빈 그래프가 된다.
