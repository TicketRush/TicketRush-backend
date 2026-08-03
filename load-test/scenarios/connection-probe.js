// (j) 동시 커넥션 상한 프로브 — 부하 생성기가 목표 VU 를 실제로 만들 수 있는지만 본다 (#549).
//
// ── 이 프로브가 답하는 질문 ────────────────────────────────────────────────
// "생성기를 AWS 안으로 옮겨야 하는가." 부하 회차를 돌리기 전에 **생성기 쪽 한계를 먼저 재는**
// 도구다. 앱은 거의 건드리지 않는다 — VU 는 목표까지 올리되 요청은 30초에 한 번뿐이라, 1만 VU
// 에서도 RPS 는 333 이고 커넥션만 1만 개가 유지된다.
//
// 이 구분이 핵심이다. 회차가 무너졌을 때 '앱이 못 버틴 것' 과 '생성기가 못 만든 것' 은 그래프가
// 비슷하게 생겼는데 결론이 정반대다. 회차 전에 이걸 떼어 재 두면 그 혼동이 없다.
//
// ── 실제로 무엇을 뒤집었나 ─────────────────────────────────────────────────
// ADR 0010 은 "1만 VU 는 로컬에서 불가능하다" 며 생성기를 AWS 임시 EC2 로 옮기기로 결정했고,
// 근거로 ① 가정용 업로드 대역 ② Docker NAT·포트 고갈 ③ 가정 공유기 세션 테이블을 들었다.
// 이 프로브가 셋 다 반증했다(2026-08-01, 6분 30초, 비용 0):
//   probe_connect_failed 0.00% (0/99,944) / 서버측 established 정확히 10,000 을 3분 이상 유지
//   data_sent 30 MB = 74 kB/s (0.6 Mbps) — 대역은 근처에도 못 갔다
//   ephemeral 포트 28,231 개 · 컨테이너 nofile 1,048,576 — 고갈 여유
// 그래서 회차 B 를 로컬 k6 로 마쳤고 ADR 0010 은 '기각됨' 이 됐다.
// 증적: load-tests/k6/results/260801-549-queue-flood/ (metadata.txt 의 PROBE 절)
//
// ⚠ **이 프로브가 통과했다고 회차가 통과하는 것은 아니다.** 커넥션 1만과 목표 RPS 를 동시에
//   요구하는 것은 이 프로브가 재지 않는다(여기서는 333 RPS 뿐이다). 회차 B 가 실제로 그 조합에서
//   무너졌다 — 다만 무너진 쪽은 생성기가 아니라 대상이었고, 그 구분이 이 프로브 덕에 가능했다.
//
// ── 실행 ───────────────────────────────────────────────────────────────────
//   docker run --rm -v $PWD/load-test:/scripts:ro --ulimit nofile=1048576:1048576 \
//     grafana/k6:latest run -e BASE_URL=https://api.ticketrush.store \
//     -e PROBE_VUS=10000 /scripts/scenarios/connection-probe.js
//
// 서버측에서 함께 센다(커넥션이 실제로 도달했는지는 대상에서 봐야 한다):
//   ss -tn state established '( sport = :443 )' | tail -n +2 | wc -l
// 프록시 요청 1건이 클라이언트+업스트림 2슬롯을 먹으므로, 진행 중 요청이 있으면 이 값은 VU 수를
// 넘는다(회차 B-1 에서 VU 1만에 established 16,701 이 나온 이유다).
import http from 'k6/http';
import { sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { BASE_URL } from '../config/env.js';

const TARGET = Number(__ENV.PROBE_VUS || 10000);
// nginx keepalive_timeout 기본값(75초)보다 짧아야 한다 — 길면 커넥션이 폴링 사이에 끊겨
// '유지되는 커넥션 수' 가 아니라 '재접속 빈도' 를 재게 된다.
const IDLE = Number(__ENV.PROBE_IDLE_SECONDS || 30);
// 앱 부하를 최소로 두려면 DB·Redis 를 타지 않는 경로여야 한다.
const PATH = __ENV.PROBE_PATH || '/actuator/health';

export const options = {
  scenarios: {
    hold: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { target: TARGET, duration: __ENV.PROBE_RAMP || '3m' },
        { target: TARGET, duration: __ENV.PROBE_HOLD || '3m' },
        { target: 0, duration: '30s' },
      ],
      gracefulRampDown: '10s',
    },
  },
  // 임계값을 두지 않는다 — 깨지는 지점을 보는 것이 목적이라 조기 중단시키면 안 된다.
  thresholds: {},
};

// 이 프로브의 유일한 판정축. status 0 은 HTTP 응답이 아니라 **커넥션이 안 맺어진 것**이다
// (포트 고갈·공유기 세션 테이블·dial timeout). 4xx/5xx 는 앱 이야기라 여기서 구분해 둔다.
const connectFailed = new Rate('probe_connect_failed');
const connecting = new Trend('probe_connecting_ms', true);

export default function () {
  const res = http.get(`${BASE_URL}${PATH}`, {
    headers: { 'Accept-Encoding': 'gzip' },
    tags: { name: 'probe' },
  });
  connectFailed.add(res.status === 0);
  connecting.add(res.timings.connecting);
  sleep(IDLE);
}
