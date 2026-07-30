// (g) 좌석 상태별 집계 조회 — 계단식 포화점 탐색 (#403).
//
// ── 왜 좌석 수 전제가 측정의 성립 조건인가 ──────────────────────────────────
// SeatRepository.getStatusCountsByPerformanceIdAndStatuses 의 WHERE 절은
// `s.performanceId = :performanceId` 하나뿐이다(SeatRepository.java:18-32). 매칭 행 전부를 훑어
// CASE 를 평가하므로 응답 시간이 공연당 좌석 수에 정비례한다 — 좌석 수를 고정하지 않은 수치는
// 해석할 수 없고 다른 환경에서 재현도 불가능하다. 그래서 COUNTS_EXPECTED_SEATS 로 규모를 검증한다.
//
// ⚠ #521 이 idx_seat_performance_id_status_hold_expired_at 로 이 스캔을 index-only 로 바꿨다.
//   스캔 몫은 줄었지만 좌석 수 비례 자체가 사라진 것은 아니다(인덱스 엔트리를 여전히 좌석 수만큼
//   읽는다). 회차를 비교할 때는 인덱스 적용 여부를 metadata 에 반드시 기록한다 — #403 은 인덱스
//   없는 상태의 수치다.
//
// ── HOLD 비율이 왜 필요한가 ─────────────────────────────────────────────────
// 집계는 만료 HOLD(holdExpiredAt <= now)를 AVAILABLE 로 선반영한다. 좌석이 전부 AVAILABLE 이면
// hold_expired_at 이 NULL 이라 datetime 비교가 사실상 생략되어 실제보다 낙관적인 값이 나온다.
// seed_seat_counts.sql 이 SOLD/HOLD 를 섞는 이유이고, 스케일 A·B 는 같은 비율이어야 두 곡선을
// 겹칠 수 있다.
//
// ── 포화 판정 ───────────────────────────────────────────────────────────────
// 도착률을 올려도 실제 RPS 가 늘지 않음 + dropped_iterations 발생 + 호스트 CPU 수렴.
// ⚠ 이 구성은 단일 EC2(2 vCPU)에 앱 8개 + Kafka·MySQL·Redis·관측 스택이 동거한다. #509 가
//   호스트 CPU 99% 가 seat-service 의 스레드 상한보다 2분 30초 먼저 온다는 것을 확정했다.
//   따라서 여기서 나오는 값은 'seat-counts 의 한계' 가 아니라 '이 구성에서 도달한 지점' 이다.
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import {
  BASE_URL,
  PERF_ID,
  COUNTS_STAGE_RATES,
  COUNTS_STAGE_DURATION,
  COUNTS_EXPECTED_SEATS,
  COUNTS_COMPARE,
  COUNTS_PRE_ALLOCATED_VUS,
  COUNTS_MAX_VUS,
} from '../config/env.js';
import { baseOptions } from '../config/options.js';
import { jsonField } from '../lib/json.js';

// 계단 사이 전환 램프. ramping-arrival-rate 의 stage 는 target 까지 duration 동안 선형 변화하므로
// "계단" 을 만들려면 (짧은 램프 + 유지) 두 개를 넣어야 한다. openrun-e2e.js 와 같은 형태다.
const STEP_RAMP = '10s';

function rampStages() {
  const rates = COUNTS_STAGE_RATES; // 오타·빈 값은 config/env.js 가 로드 시점에 끊는다
  const stages = [{ target: rates[0], duration: COUNTS_STAGE_DURATION }];
  for (let i = 1; i < rates.length; i++) {
    stages.push({ target: rates[i], duration: STEP_RAMP });
    stages.push({ target: rates[i], duration: COUNTS_STAGE_DURATION });
  }
  return stages;
}

export const options = {
  scenarios: {
    counts: {
      executor: 'ramping-arrival-rate',
      timeUnit: '1s',
      startRate: COUNTS_STAGE_RATES[0],
      preAllocatedVUs: COUNTS_PRE_ALLOCATED_VUS,
      maxVUs: COUNTS_MAX_VUS,
      stages: rampStages(),
    },
  },
  // 값은 재정의하지 않는다(openrun-e2e.js 와 같은 선) — 포화 구간에서 p(95)<800 이 깨지는 것
  // 자체가 관측 대상이다. dropped_iterations 도 threshold 에 넣지 않는다. 이 회차에서 dropped 는
  // 실패가 아니라 포화 신호이므로 요약에서 읽는다.
  thresholds: baseOptions.thresholds,
};

const countsDuration = new Trend('seat_counts_duration', true);
const layoutsDuration = new Trend('seat_layouts_duration', true); // COUNTS_COMPARE=1 에서만 채워진다
const scaleMismatch = new Rate('seat_counts_scale_mismatch'); // >0 이면 이 회차는 무효

export function setup() {
  // 회차 종류가 -e 한 줄로 갈리므로 실제 적용된 값을 요약 로그에 남긴다(증적).
  console.log(
    `[setup] perfId=${PERF_ID} stages=${COUNTS_STAGE_RATES.join(',')} x ${COUNTS_STAGE_DURATION} ` +
      `expectedSeats=${COUNTS_EXPECTED_SEATS || 'unchecked'} compare=${COUNTS_COMPARE}`,
  );
}

export default function () {
  const res = http.get(`${BASE_URL}/api/v1/seat/${PERF_ID}/seat-counts`, {
    // 실제 브라우저는 항상 보낸다. k6 는 Go 의 투명 압축을 끄고 직접 관리하므로 명시하지 않으면
    // 비압축 응답을 받는다(#505). 응답이 203 B 라 효과는 미미하지만 조건은 다른 회차와 맞춘다.
    headers: { 'Accept-Encoding': 'gzip' },
    tags: { name: 'seat_counts' },
  });
  countsDuration.add(res.timings.duration);

  const total = jsonField(res, 'result.total_count');
  check(res, { 'seat-counts 200': (r) => r.status === 200 });

  // 규모 검증. 엉뚱한 공연을 재거나 시딩이 덜 된 채 회차를 돌리면 곡선이 통째로 거짓이 된다.
  // 여기서 abort 하지 않는 것은 포화 구간의 타임아웃(total=null)까지 무효로 몰지 않기 위해서다 —
  // 200 인데 값이 다른 경우만 무효 신호로 센다. 요약에서 이 Rate 가 0 이 아니면 회차를 폐기한다.
  if (COUNTS_EXPECTED_SEATS > 0 && res.status === 200) {
    scaleMismatch.add(total !== COUNTS_EXPECTED_SEATS);
  }

  if (!COUNTS_COMPARE) {
    return;
  }

  // 비교군 — 같은 공연·같은 부하·같은 iteration 에서 seat-layouts 를 잰다. 두 경로의 차이는
  // 응답 크기와 직렬화뿐이고 DB 접근 행수는 같다(둘 다 performance_id 로 전 행 스캔).
  const layouts = http.get(`${BASE_URL}/api/v1/seat/${PERF_ID}/seat-layouts`, {
    headers: { 'Accept-Encoding': 'gzip' },
    tags: { name: 'seat_layouts_compare' },
  });
  layoutsDuration.add(layouts.timings.duration);
  check(layouts, { 'seat-layouts 200': (r) => r.status === 200 });
}
