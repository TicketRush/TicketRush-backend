// k6 시나리오 공통 환경변수 파싱 + 기본값. 실행 시 `-e KEY=VALUE` 또는 K6_* 로 덮는다.
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080'; // 게이트웨이

export const PERF_ID = Number(__ENV.PERF_ID || 1); // 시딩한 공연 ID

// 부하테스트 전용 계정(seed_load.sql 이 시딩한다). 비밀번호는 평문을 커밋하지 않으므로 실행 인자로 준다.
export const LOAD_USER_EMAIL = __ENV.LOAD_USER_EMAIL || 'loadtest@ticketrush.local';
export const LOAD_USER_PASSWORD = __ENV.LOAD_USER_PASSWORD || '';

// booking 시나리오가 예매 시도할 seat_id 범위(시딩 규모에 맞춰 넓게 잡아 좌석 고갈 완화)
export const SEAT_ID_MIN = Number(__ENV.SEAT_ID_MIN || 1);
export const SEAT_ID_MAX = Number(__ENV.SEAT_ID_MAX || 100);

// seat-contention 시나리오가 집중 타격할 단일 좌석. 실행마다 리셋이 필요하다(가이드 §10.2).
export const TARGET_SEAT_ID = Number(__ENV.TARGET_SEAT_ID || SEAT_ID_MIN);

// 부하 프로파일(램프업)
export const VUS = Number(__ENV.VUS || 50);
export const RAMP = __ENV.RAMP || '30s';
export const STEADY = __ENV.STEADY || '1m';

// ---- #402 입장 검표 (entry-spike.js / entry-duplicate-scan.js) --------------
// 검표 API는 ADMIN 권한이 필요하다. seed_entry.sql 이 이 계정을 만들고, 티켓 소유자도 같은 계정으로
// 채워서 QR 발급(소유자 검사)과 verify/check-in(ADMIN)이 토큰 하나로 통과하게 해뒀다.
// 비밀번호는 평문을 커밋하지 않으므로 실행 인자로 준다(seed 의 @admin_pw_hash 와 짝이 맞아야 한다).
export const LOAD_ADMIN_EMAIL = __ENV.LOAD_ADMIN_EMAIL || 'loadtest-admin@ticketrush.local';
export const LOAD_ADMIN_PASSWORD = __ENV.LOAD_ADMIN_PASSWORD || '';

// seed_entry.sql 이 booking_id 를 @bk_base+1 부터 연속으로 직접 지정한다(AUTO_INCREMENT 미사용).
// 시드 검증 쿼리의 booking_id_min 이 이 값과 다르면 시딩이 잘못된 것이다.
export const ENTRY_BOOKING_ID_MIN = Number(__ENV.ENTRY_BOOKING_ID_MIN || 1000001);
export const ENTRY_TICKET_COUNT = Number(__ENV.ENTRY_TICKET_COUNT || 25000);

// 스파이크 프로파일. check-in 이 티켓을 비가역 소모하므로 VU 수가 아니라 도착률(스캔/초)로 통제한다.
// 소요 티켓 ≈ BASELINE_RATE*BASELINE_DURATION*2 + PEAK_RATE*PEAK_DURATION + 램프 구간.
// baseline·회복은 이슈 완료조건상 최소 5분이며, 램프 경계 오염분 여유로 6분을 기본값으로 둔다.
export const ENTRY_BASELINE_RATE = Number(__ENV.ENTRY_BASELINE_RATE || 10);
export const ENTRY_PEAK_RATE = Number(__ENV.ENTRY_PEAK_RATE || 60);
export const ENTRY_BASELINE_DURATION = __ENV.ENTRY_BASELINE_DURATION || '6m';
export const ENTRY_PEAK_DURATION = __ENV.ENTRY_PEAK_DURATION || '3m';
export const ENTRY_SPIKE_RAMP = __ENV.ENTRY_SPIKE_RAMP || '20s';

// arrival-rate 는 VU 를 미리 할당해야 한다. 부족하면 도착률을 못 채우고 dropped_iterations 가 오른다.
// maxVUs 는 피크 도착률 x iteration 소요시간 상한으로 잡는다(60/s x 6.6s ≈ 400).
export const ENTRY_PRE_ALLOCATED_VUS = Number(__ENV.ENTRY_PRE_ALLOCATED_VUS || 20);
export const ENTRY_MAX_VUS = Number(__ENV.ENTRY_MAX_VUS || 400);

// 동일 QR 동시 다중 스캔 회차. 대상은 코호트 뒤쪽(스파이크가 도달하지 않는 구간)에서 고른다.
export const ENTRY_DUP_BOOKING_ID = Number(
  __ENV.ENTRY_DUP_BOOKING_ID || ENTRY_BOOKING_ID_MIN + ENTRY_TICKET_COUNT - 1,
);
export const ENTRY_DUP_VUS = Number(__ENV.ENTRY_DUP_VUS || 30);

// ---- #348 오픈런 스파이크 e2e (openrun-e2e.js) ------------------------------
// 콤마 목록 env 파서. 잘못된 토큰을 조용히 걸러내지 않고 끊는다.
// `filter(v => v > 0)` 로 걸러내면 `10,abc,40` 이 계단 3개에서 2개로 줄어든 채 실행되는데,
// 포화점을 계단 형상으로 판정하는 회차에서 형상이 조용히 달라지는 것은 EC2 과금 + 20분을
// 날리고도 그 사실을 모르는 결과가 된다. 다른 env 들이 단일 Number() 라 NaN 으로 곧장
// 터지는 것과 동작을 맞춘다.
function parsePositiveList(name, raw, example) {
  const tokens = String(raw)
    .split(',')
    .map((v) => v.trim())
    .filter((v) => v !== '');
  const nums = tokens.map(Number);
  if (nums.length === 0 || nums.some((v) => !(v > 0))) {
    throw new Error(`${name} 파싱 실패: '${raw}' — 양수 콤마 목록이어야 한다 (예: ${example})`);
  }
  return nums;
}

// 회차 종류. 'ramp' = 계단식 포화점 탐색, 'spike' = 오픈런 스파이크. 두 회차를 따로 돌린다.
// 한 파일에 둔 것은 여정·좌석 배정·메트릭이 완전히 같고 stages 만 다르기 때문이다.
// 오타가 나면 조용히 한쪽으로 폴백해 '정상으로 보이는 엉뚱한 회차' 가 되므로 여기서 끊는다.
export const E2E_PROFILE = __ENV.E2E_PROFILE || 'ramp';
if (E2E_PROFILE !== 'ramp' && E2E_PROFILE !== 'spike') {
  throw new Error(`E2E_PROFILE 은 'ramp' 또는 'spike' 만 가능하다 (받은 값: '${E2E_PROFILE}')`);
}

// 계단식 프로파일. 각 단계를 E2E_STAGE_DURATION 만큼 '유지' 한다(램프 없이 계단으로 올린다).
// 5분은 이슈 완료조건이자 관측 하한이다 — Prometheus 스크랩이 15초라 단계당 20 표본이 나온다.
// 포화 판정은 "도착률을 올려도 실제 RPS 가 늘지 않는 구간" + dropped_iterations 발생 + 호스트 CPU 수렴.
// 상한값 근거: #344 가 POST /api/v1/booking 단독 258 RPS 에서 호스트 CPU 99.87% 를 봤다(2 vCPU 단일 EC2).
// browse iteration 은 요청 3개라 80/s 면 이미 240 req/s 다. 실측 캘리브레이션으로 조정한다.
export const E2E_STAGE_RATES = parsePositiveList(
  'E2E_STAGE_RATES',
  __ENV.E2E_STAGE_RATES || '10,20,40,80',
  '10,20,40,80',
);
export const E2E_STAGE_DURATION = __ENV.E2E_STAGE_DURATION || '5m';

// 오픈런 스파이크 프로파일. entry-spike.js 와 같은 5단계 형태(baseline -> 급램프 -> 피크 -> 복귀).
// 오픈런은 '수 초 내' 급증이므로 램프를 10초로 둔다(검표 스파이크의 20초보다 짧다).
export const E2E_BASELINE_RATE = Number(__ENV.E2E_BASELINE_RATE || 10);
export const E2E_PEAK_RATE = Number(__ENV.E2E_PEAK_RATE || 80);
export const E2E_BASELINE_DURATION = __ENV.E2E_BASELINE_DURATION || '6m';
export const E2E_PEAK_DURATION = __ENV.E2E_PEAK_DURATION || '5m';
export const E2E_SPIKE_RAMP = __ENV.E2E_SPIKE_RAMP || '10s';

// 예매 축 도착률 = 조회 축 도착률 x 이 비율. 여정 전체를 같은 비율로 돌리면 좌석 수가 곧 부하 총량의
// 상한이 되어 계단을 끝까지 올릴 수 없다(예매는 iteration 당 좌석 1개를 비가역 소모한다).
// 실제 티켓팅도 조회:예매가 크게 기운다.
//   필요 좌석 = Σ(rate x ratio x stage 길이), 전환 램프 구간 포함.
//   기본값 기준(k6 inspect 로 확정): ramp 회차 9,210석 / spike 회차 6,420석.
export const E2E_PURCHASE_RATIO = Number(__ENV.E2E_PURCHASE_RATIO || 0.2);

// 예매 대상 공연 ID 목록(콤마 구분). 공연 1건의 좌석만으로는 계단식 회차를 못 버티므로 여러 공연에
// 걸쳐 좌석을 확보한다. 조회 축이 집중하는 '인기 공연'은 PERF_ID 로 따로 지정한다(핫 로우 재현).
// 시딩 후 실제 ID 를 확인해 넘긴다 — 런북 §13.2.
export const E2E_PURCHASE_PERF_IDS = parsePositiveList(
  'E2E_PURCHASE_PERF_IDS',
  __ENV.E2E_PURCHASE_PERF_IDS || String(PERF_ID),
  '3,4,5,6,7',
);

// arrival-rate 는 VU 를 미리 할당해야 한다. 부족하면 도착률을 못 채우고 dropped_iterations 가 오른다.
// 이 회차에서 dropped 는 '포화 신호' 이기도 해서 VU 부족과 구분해야 한다 — preAllocated 를 넉넉히
// 잡아 두고, 그래도 dropped 가 나면 그때는 대상 포화로 읽는다(런북 §13.5 무효 판정).
export const E2E_PRE_ALLOCATED_VUS = Number(__ENV.E2E_PRE_ALLOCATED_VUS || 100);
export const E2E_MAX_VUS = Number(__ENV.E2E_MAX_VUS || 600);
