// (f) 오픈런 스파이크 e2e 종합 부하 — 게이트웨이를 통과하는 사용자 여정 전체 (#348).
//
// ── 이슈 본문의 여정과 실제 구조가 다른 지점 2개 ──────────────────────────────
// 1. 좌석 선점(HOLD)은 HTTP API 가 아니다. SeatController 는 GET 뿐이고, HOLD 진입점은
//    seat-service 의 BookingCreatedEventListener(Kafka) 하나다. 즉 여정은
//    "좌석맵 조회 -> HOLD -> 예매 생성" 이 아니라 "좌석맵 조회 -> 예매 생성 -> (비동기) HOLD" 다.
//    k6 가 칠 수 있는 것은 예매 생성까지이고, HOLD 는 유입 축이 아니라 처리 축
//    (outbox backlog + consumer lag)에서 본다. 근거는 docs/load-test-guide.md §10.1.
// 2. 결제·티켓 발급은 이 회차에서 뺀다. StubPaymentApprovalClient 가 @Profile("!prod") 이고
//    javadoc 이 "운영(prod) 프로파일에서는 환경변수 설정과 무관하게 절대 활성화되지 않는다" 고
//    못박는다. ADR 0004 가 정한 측정 대상은 prod 단독 배포본이라 stub 경로가 원천 차단되고,
//    남는 것은 실 Toss 호출뿐인데 paymentKey 는 PG 가 발급하는 값이라 k6 가 만들 수 없다.
//    웹훅도 paymentKey 로 PG 에 재조회해 진위를 검증한다. 우회 경로가 없다.
//
// ── 조회 축과 예매 축을 나눈 이유 ────────────────────────────────────────────
// 예매는 iteration 당 좌석 1개를 비가역 소모한다. 여정 전체를 같은 도착률로 돌리면 좌석 수가 곧
// 부하 총량의 상한이 되어 계단을 끝까지 올릴 수 없다. 그래서 scenario 를 둘로 나누고 예매 축만
// E2E_PURCHASE_RATIO 로 낮춘다. 실제 티켓팅도 조회:예매가 크게 기운다.
//   browse   : 공연 목록 -> 인기 공연 상세 -> 인기 공연 좌석맵   (좌석 소모 없음)
//   purchase : 예매 생성                                        (좌석 1개 소모)
//
// ── 좌석 배정 ────────────────────────────────────────────────────────────────
// setup() 에서 대상 공연들의 좌석맵을 한 번씩 읽어 AVAILABLE 좌석의 (perfId, min, step, count) 를
// 만들고, iteration 인덱스를 거기에 누적 매핑한다. 배열 전체를 setup 데이터로 넘기지 않는 것은
// k6 가 그 값을 VU 마다 복사하기 때문이다(좌석 2만이면 생성기 메모리를 먹는다).
//
// ⚠ seat_id 는 공연 안에서 '연속' 이 아니라 '등차' 다. seed_load.sql 의
//   `FROM seat_layout sl CROSS JOIN r CROSS JOIN c` 를 MySQL 이 (r,c) 바깥 루프로 실행해서
//   공연들이 인터리브로 삽입되기 때문이다 — 공연 10건을 시딩하면 step 이 10 이 된다
//   (로컬 6,120석 실측: perf 8 의 seat_id 가 121,131,141,... 로 distinct_step=1, step=10).
//   그래서 간격을 가정하지 않고 응답에서 '읽어' 검증한다. 간격이 일정하지 않으면 시작 전에 중단한다.
//
// 좌석 경합 자체는 #344 가 이미 쟀다. 이 회차의 목적은 포화점이므로 좌석을 유일 배정해 409 를
// 배제하고 순수 처리량을 본다. 그래도 409 가 나오면 리셋 누락이거나 다른 회차와 코호트가 겹친 것이다.
import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import {
  BASE_URL,
  PERF_ID,
  LOAD_USER_EMAIL,
  LOAD_USER_PASSWORD,
  E2E_PROFILE,
  E2E_STAGE_RATES,
  E2E_STAGE_DURATION,
  E2E_BASELINE_RATE,
  E2E_PEAK_RATE,
  E2E_BASELINE_DURATION,
  E2E_PEAK_DURATION,
  E2E_SPIKE_RAMP,
  E2E_PURCHASE_RATIO,
  E2E_PURCHASE_PERF_IDS,
  E2E_PRE_ALLOCATED_VUS,
  E2E_MAX_VUS,
} from '../config/env.js';
import { baseOptions } from '../config/options.js';
import { login } from '../lib/auth.js';
import { jsonField } from '../lib/json.js';

// 409(좌석 선점 불가)는 정상 경합이라 실패가 아니다 — 이슈의 "정상경합 제외 에러율 < 1%" 기준.
// 200 을 반드시 함께 넣는다: 이 콜백은 파일 스코프가 아니라 run 전역이라 lib/auth.js 의 로그인(200)까지 덮는다.
// 201 은 예매 생성 성공이다. 그 외(4xx/5xx/타임아웃)는 http_req_failed 가 그대로 잡는다.
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

// 계단 사이 전환 램프. ramping-arrival-rate 의 stage 는 target 까지 duration 동안 선형 변화하므로
// "계단" 을 만들려면 (짧은 램프 + 유지) 두 개를 넣어야 한다. 유지 구간 5분 대비 3% 라 판정에
// 영향이 없고, 측정 창은 유지 구간만 쓴다(런북 §13.4).
const STEP_RAMP = '10s';

function rampStages(rate) {
  // 빈 값·오타는 config/env.js 의 parsePositiveList 가 로드 시점에 이미 끊는다.
  const rates = E2E_STAGE_RATES;
  const stages = [{ target: rate(rates[0]), duration: E2E_STAGE_DURATION }];
  for (let i = 1; i < rates.length; i++) {
    stages.push({ target: rate(rates[i]), duration: STEP_RAMP });
    stages.push({ target: rate(rates[i]), duration: E2E_STAGE_DURATION });
  }
  return stages;
}

function spikeStages(rate) {
  return [
    { target: rate(E2E_BASELINE_RATE), duration: E2E_BASELINE_DURATION }, // baseline (>=5분)
    { target: rate(E2E_PEAK_RATE), duration: E2E_SPIKE_RAMP }, // 오픈런 급램프
    { target: rate(E2E_PEAK_RATE), duration: E2E_PEAK_DURATION }, // 피크 유지 (>=5분)
    { target: rate(E2E_BASELINE_RATE), duration: E2E_SPIKE_RAMP }, // 램프다운
    { target: rate(E2E_BASELINE_RATE), duration: E2E_BASELINE_DURATION }, // 회복 (>=5분)
  ];
}

const browseRate = (r) => r;
// 도착률이 0 이 되면 그 scenario 는 아무것도 못 돌린다. 비율이 작아도 최소 1/s 는 보장한다.
const purchaseRate = (r) => Math.max(1, Math.round(r * E2E_PURCHASE_RATIO));

const stagesFor = E2E_PROFILE === 'spike' ? spikeStages : rampStages;
const purchaseVus = (n, floor) => Math.max(floor, Math.round(n * E2E_PURCHASE_RATIO));

export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-arrival-rate',
      timeUnit: '1s',
      startRate: browseRate(E2E_PROFILE === 'spike' ? E2E_BASELINE_RATE : E2E_STAGE_RATES[0]),
      preAllocatedVUs: E2E_PRE_ALLOCATED_VUS,
      maxVUs: E2E_MAX_VUS,
      stages: stagesFor(browseRate),
      exec: 'browse',
    },
    purchase: {
      executor: 'ramping-arrival-rate',
      timeUnit: '1s',
      startRate: purchaseRate(E2E_PROFILE === 'spike' ? E2E_BASELINE_RATE : E2E_STAGE_RATES[0]),
      preAllocatedVUs: purchaseVus(E2E_PRE_ALLOCATED_VUS, 10),
      maxVUs: purchaseVus(E2E_MAX_VUS, 50),
      stages: stagesFor(purchaseRate),
      exec: 'purchase',
    },
  },
  // scenarios 를 쓰면 top-level stages 를 못 쓰므로 공통 옵션에서 thresholds 만 가져온다.
  // 값은 재정의하지 않는다(entry-spike.js 와 같은 선) — 포화 구간에서 p(95)<800 이 깨지는 것 자체가
  // 관측 대상이다. dropped_iterations 도 threshold 에 넣지 않는다. 이 회차에서 dropped 는 실패가
  // 아니라 포화 신호이므로 요약에서 읽는다.
  thresholds: baseOptions.thresholds,
};

const perfListDuration = new Trend('e2e_perf_list_duration', true);
const perfDetailDuration = new Trend('e2e_perf_detail_duration', true);
const seatLayoutsDuration = new Trend('e2e_seat_layouts_duration', true);
const bookingDuration = new Trend('e2e_booking_duration', true);
const browseJourneyDuration = new Trend('e2e_browse_journey_duration', true); // 조회 여정 3요청 합

const bookingCreated = new Rate('e2e_booking_created'); // 201 = 예매 생성 성공
const seatConflict = new Rate('e2e_seat_conflict'); // 409 = 좌석 선점 불가(유일 배정이면 0 이어야 한다)
const seatExhausted = new Rate('e2e_seat_exhausted'); // >0 이면 이 회차는 무효(좌석 부족)

export function setup() {
  const token = login(LOAD_USER_EMAIL, LOAD_USER_PASSWORD);

  // 예매 대상 공연별로 AVAILABLE 좌석 코호트를 만든다. 부하 전 1회라 여기서 드는 비용은 무관하다.
  const cohorts = [];
  for (const perfId of E2E_PURCHASE_PERF_IDS) {
    const res = http.get(`${BASE_URL}/api/v1/seat/${perfId}/seat-layouts`, {
      tags: { name: 'setup_seat_layouts' },
    });
    const seats = jsonField(res, 'result');
    if (!Array.isArray(seats)) {
      fail(`setup: 좌석맵 조회 실패 perfId=${perfId} status=${res.status}`);
    }

    const ids = seats.filter((s) => s.seat_status === 'AVAILABLE').map((s) => s.seat_id);
    if (ids.length === 0) {
      fail(`setup: perfId=${perfId} 에 AVAILABLE 좌석이 없다. reset_e2e.sql 을 먼저 돌린다`);
    }
    ids.sort((a, b) => a - b);

    // 간격이 일정해야 (min + step*offset) 산술 배정이 성립한다. 깨져 있다면 중간 좌석을 다른 코호트가
    // 점유한 것이고, 그대로 돌리면 409 가 '정상 경합' 으로 위장되어 측정이 조용히 오염된다.
    const min = ids[0];
    const step = ids.length > 1 ? ids[1] - ids[0] : 1;
    for (let i = 1; i < ids.length; i++) {
      if (ids[i] - ids[i - 1] !== step) {
        fail(
          `setup: perfId=${perfId} 의 AVAILABLE seat_id 간격이 일정하지 않다 ` +
            `(step=${step} 인데 ids[${i}]=${ids[i]}). reset_e2e.sql 후 재시도한다`,
        );
      }
    }
    cohorts.push({ perfId, seatIdMin: min, seatIdStep: step, count: ids.length });
  }

  // 실제 적용된 프로파일을 증적에 남긴다. -e 한 줄로 회차 종류가 갈리므로 요약 로그만 보고도
  // "어느 회차였는지" 가 확정돼야 한다(오타는 env.js 가 이미 끊는다).
  const total = cohorts.reduce((sum, c) => sum + c.count, 0);
  console.log(
    `[setup] profile=${E2E_PROFILE} 예매 가능 좌석 ${total}석 / 공연 ${cohorts.length}건, ` +
      `인기 공연=${PERF_ID}`,
  );
  return { token, cohorts };
}

// 조회 축 — 인기 공연 1건에 집중시켜 핫 로우(같은 performance/seat 인덱스 반복 조회)를 재현한다.
// 조회 엔드포인트는 전부 permitAll 이지만 토큰을 붙인다. 실제 사용자는 로그인 상태로 조회하고,
// 게이트웨이 JwtAuthenticationFilter 의 검증 비용도 여정에 포함되어야 한다.
export function browse(data) {
  const auth = { headers: { Authorization: `Bearer ${data.token}` } };
  const started = Date.now();

  const list = http.get(`${BASE_URL}/api/v1/performance`, {
    ...auth,
    tags: { name: 'e2e_perf_list' },
  });
  perfListDuration.add(list.timings.duration);

  const detail = http.get(`${BASE_URL}/api/v1/performance/${PERF_ID}`, {
    ...auth,
    tags: { name: 'e2e_perf_detail' },
  });
  perfDetailDuration.add(detail.timings.duration);

  // 좌석맵은 페이지네이션 없이 그 공연의 전 좌석을 반환한다. 응답 크기가 곧 이 경로의 비용이고
  // #469(좌석 조회 캐싱)가 그래서 후속으로 잡혀 있다 — 앱의 실제 특성이므로 그대로 잰다.
  const seats = http.get(`${BASE_URL}/api/v1/seat/${PERF_ID}/seat-layouts`, {
    ...auth,
    tags: { name: 'e2e_seat_layouts' },
  });
  seatLayoutsDuration.add(seats.timings.duration);

  browseJourneyDuration.add(Date.now() - started);
  check(seats, { 'browse journey ok(200)': (r) => r.status === 200 });
}

export function purchase(data) {
  // exec.scenario.iterationInTest = 이 시나리오의 전역 0-base 단조 카운터. booking-create.js 의
  // __VU*1000+__ITER 은 arrival-rate 에서 쓰면 안 된다 — VU 를 풀에서 재사용해 인덱스가 충돌하고
  // 비단조라 필요 좌석 수를 계산할 수 없다(entry-spike.js 가 같은 함정을 기록해 뒀다).
  const idx = exec.scenario.iterationInTest;
  const seat = pickSeat(data.cohorts, idx);
  seatExhausted.add(seat === null);
  if (seat === null) {
    // 좌석 고갈. exec.test.abort() 를 쓰지 않는다 — 회복 구간 도중에 abort 하면 회차 전체를 버려야
    // 한다. 요약에서 e2e_seat_exhausted > 0 이면 그 회차를 무효 처리한다(런북 §13.5).
    return;
  }

  const res = http.post(
    `${BASE_URL}/api/v1/booking`,
    JSON.stringify({ performance_id: seat.perfId, seat_id: seat.seatId }),
    {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` },
      tags: { name: 'e2e_booking_create' },
    },
  );
  bookingDuration.add(res.timings.duration);
  bookingCreated.add(res.status === 201);
  seatConflict.add(res.status === 409);

  check(res, { 'booking created(201)': (r) => r.status === 201 });
}

// idx 를 코호트에 누적으로 매핑한다. 앞 공연을 다 쓰면 다음 공연으로 넘어가고, 전부 쓰면 null.
function pickSeat(cohorts, idx) {
  let offset = idx;
  for (const c of cohorts) {
    if (offset < c.count) {
      return { perfId: c.perfId, seatId: c.seatIdMin + c.seatIdStep * offset };
    }
    offset -= c.count;
  }
  return null;
}
