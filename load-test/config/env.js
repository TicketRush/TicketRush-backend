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

// ---- #403 좌석 상태 집계 (seat-counts.js) -----------------------------------
// 계단식 포화점 탐색. openrun-e2e 의 ramp 회차와 같은 형태이고 단계당 5분도 같다
// (이슈 완료조건이자 관측 하한 — Prometheus 스크랩 15초라 단계당 20표본).
// 상한 근거: seat-counts 응답은 203 bytes 로 seat-layouts(2,080석 기준 약 230 KB)보다 3자리
// 작다. 회선·직렬화가 아니라 집계 스캔만 남으므로 seat-layouts 계단(10~80)보다 위를 본다.
// 실측 캘리브레이션으로 조정한다.
export const COUNTS_STAGE_RATES = parsePositiveList(
  'COUNTS_STAGE_RATES',
  __ENV.COUNTS_STAGE_RATES || '20,40,80,160',
  '20,40,80,160',
);
export const COUNTS_STAGE_DURATION = __ENV.COUNTS_STAGE_DURATION || '5m';

// 시딩 규모 검증값. seed_seat_counts.sql 검증 쿼리의 expect_total_count 를 그대로 넘긴다.
// 0 이면 검증을 생략한다 — 다만 규모가 곧 이 측정의 전제이므로 실회차에서는 반드시 지정한다.
// 응답이 조용히 빈 값이 되거나 엉뚱한 공연을 재는 사고를 여기서 잡는다.
export const COUNTS_EXPECTED_SEATS = Number(__ENV.COUNTS_EXPECTED_SEATS || 0);

// 1 이면 같은 iteration 에서 seat-layouts 도 호출해 비용을 비교한다(런북 §14.5 비교 회차 전용).
// 상시로 켜면 안 된다 — 좌석 3,000석 응답이 CPU·메모리를 삼켜 집계 쿼리 수치를 오염시킨다
// (#509 가 2,080석 seat-layouts 로 seat-service 를 cgroup OOM 까지 몰고 간 경로다).
export const COUNTS_COMPARE = __ENV.COUNTS_COMPARE === '1';

export const COUNTS_PRE_ALLOCATED_VUS = Number(__ENV.COUNTS_PRE_ALLOCATED_VUS || 50);
export const COUNTS_MAX_VUS = Number(__ENV.COUNTS_MAX_VUS || 400);

// ---- #403 SSE 대량 구독 (seat-sse-fanout.js, k6-sse 이미지 전용) ------------
// 구독 대상 공연. seed_seat_counts.sql 이 만든 SSE 코호트의 performance_id 를 넘긴다.
export const SSE_PERF_ID = Number(__ENV.SSE_PERF_ID || PERF_ID);

// 동시 구독자 계단. VU 1개 = SSE 커넥션 1개다(sse.open 이 커넥션이 닫힐 때까지 블로킹한다).
// 단계당 유지시간이 10분인 것은 이슈 완료조건이다 — 장기 커넥션의 누수·타임아웃 전 구간을 본다.
export const SSE_SUBSCRIBER_STEPS = parsePositiveList(
  'SSE_SUBSCRIBER_STEPS',
  __ENV.SSE_SUBSCRIBER_STEPS || '100,300,600',
  '100,300,600',
);
export const SSE_STEP_DURATION = __ENV.SSE_STEP_DURATION || '10m';
export const SSE_STEP_RAMP = __ENV.SSE_STEP_RAMP || '30s';

// 좌석 상태 변경(=이벤트) 발생률. 이 회차의 통제 변수다 — 구독자 수만 바꾸고 이벤트율은 고정해야
// '구독자 수 대비 전파 지연' 곡선이 성립한다. POST /api/v1/booking 1건 = (Kafka 경유) HOLD 1건
// = 이벤트 1건이고, iteration 당 좌석 1개를 비가역 소모한다.
//   필요 좌석 ≈ SSE_MUTATE_RATE x 전체 회차 길이(초). 기본값 기준 5/s x 약 33분 ≈ 9,900석.
export const SSE_MUTATE_RATE = Number(__ENV.SSE_MUTATE_RATE || 5);

// probe = 전파 지연 SSOT. 자기가 구독하고 자기가 예매를 걸어, 자기 seat_id 이벤트가 돌아오는
// 시각차를 같은 k6 프로세스의 시계로 잰다(EC2 와 로컬의 시계 차를 타지 않는다).
// 매 iteration 이 새 커넥션이라 구독자 목록의 '뒤쪽' 에 등록된다 = 팬아웃 전 구간을 통과한 값.
export const SSE_PROBE_PER_MINUTE = Number(__ENV.SSE_PROBE_PER_MINUTE || 2);
// probe 가 자기 이벤트를 이 시간 안에 못 받으면 포기하고 커넥션을 닫는다(미수신으로 집계).
export const SSE_PROBE_TIMEOUT_MS = Number(__ENV.SSE_PROBE_TIMEOUT_MS || 20000);

// ---- #472 대기열 (waiting-room.js) -----------------------------------------
// 회차 프로파일. 두 회차의 목적이 완전히 다르다 — 오타로 엉뚱한 회차를 20분 돌리지 않도록 로드
// 시점에 끊는다(E2E_PROFILE 과 같은 선).
//   status : 상태 확인 경로 단독 부하로 R(경로 용량)을 실측한다. ADR 0009 §3 이 "R 은 아직
//            실측되지 않았다" 고 적어 둔 그 값이고, 이 회차가 T 를 확정해 ADR 을 갱신한다.
//   flood  : 1만 VU 유입 제어 검증. 예매 경로 RPS 가 입장 허용량에서 평평한지를 본다.
const QUEUE_PROFILES = ['status', 'flood'];
export const QUEUE_PROFILE = __ENV.QUEUE_PROFILE || 'status';
if (!QUEUE_PROFILES.includes(QUEUE_PROFILE)) {
  throw new Error(`QUEUE_PROFILE 은 ${QUEUE_PROFILES.join('|')} 중 하나여야 한다: '${QUEUE_PROFILE}'`);
}

// 대기열을 붙일 공연. 지표가 공연별로 쪼개지지 않으므로(ID 태그 금지) 회차당 공연 하나만 쓴다.
export const QUEUE_PERF_ID = Number(__ENV.QUEUE_PERF_ID || PERF_ID);

// --- status 회차 -------------------------------------------------------------
// 계단 시작점 근거: 이 경로는 GET(대기 토큰) + ZRANK 두 번이고 DB 를 타지 않는다. 게이트웨이 홉 +
// DB 집계를 포함한 #529 seat-counts 포화점 396.75 RPS 보다 빠를 수밖에 없으므로 그 위에서 시작한다.
export const QUEUE_STATUS_STAGE_RATES = parsePositiveList(
  'QUEUE_STATUS_STAGE_RATES',
  __ENV.QUEUE_STATUS_STAGE_RATES || '200,400,800,1600',
  '200,400,800,1600',
);
export const QUEUE_STATUS_STAGE_DURATION = __ENV.QUEUE_STATUS_STAGE_DURATION || '5m';
export const QUEUE_STATUS_PRE_ALLOCATED_VUS = Number(__ENV.QUEUE_STATUS_PRE_ALLOCATED_VUS || 100);
export const QUEUE_STATUS_MAX_VUS = Number(__ENV.QUEUE_STATUS_MAX_VUS || 800);

// ZRANK 는 skiplist 탐색이라 ZSET 크기에 로그 비례한다. 빈 대기열을 재면 실회차보다 낙관적인
// 값이 나오므로, setup() 이 이만큼을 미리 채운 뒤 계단을 시작한다.
export const QUEUE_PRELOAD_SIZE = Number(__ENV.QUEUE_PRELOAD_SIZE || 10000);

// --- flood 회차 --------------------------------------------------------------
// iteration 1개 = 대기자 1명. 진입 후에는 서버가 지시한 주기로만 폴링하므로 커넥션을 물고 있지 않다.
//
// ⚠ 2026-08-14(#555)에 ramping-vus → ramping-arrival-rate 로 옮겼다. #554 가 자기 한계로
//   확정한 사실이다(report §8): ramping-vus + VU 당 1회 여정 가드는 실효 도착률이
//   QUEUE_FLOOD_VUS / QUEUE_FLOOD_RAMP = 10,000/300s = 33.3/s 상수인 **단일 동작점**이다.
//   VU 를 올려도 여정이 1회로 끝나 도착률이 안 오른다 — 다이얼이 없어 계단을 만들 수 없다.
//
// 계단은 k6 stages 가 아니다. QUEUE_ADMIT_RATE 는 서버값(gateway application.yml)이고 반영에
// 게이트웨이 --force-recreate 가 필요하므로(런북 §16.4 G2), **계단 하나 = 회차 하나**다.
// 여기 stages 는 계단이 아니라 코호트 주입기이고, 계단 사이의 통제 변수다.

// 도착률(여정/s). ⚠ 회차의 QUEUE_ADMIT_RATE 보다 충분히 커야 한다 — 도착률 ≤ admit 이면
// 대기가 안 쌓이고 예매 RPS 가 도착률에서 평평해져, admit 이 아니라 부하 모델을 재게 된다.
// 그것이 #554 가 걸린 함정이다(§10.1 "33.73 은 부하 모델이 만든 상한이지 시스템의 한계가 아니다").
// 기본값은 계단 1회차(admit 20)용 = 2 x admit. 계단마다 함께 올린다(런북 §16.7 표).
export const QUEUE_ARRIVAL_RATE = Number(__ENV.QUEUE_ARRIVAL_RATE || 40);
// 초 단위 숫자로 받는다(다른 시나리오의 duration 문자열과 다르다) — setup() 이 계획 iteration 을
// 도착률 x 시간으로 계산해 코호트와 대조하기 때문이다. 문자열이면 파서를 하나 더 들여야 한다.
export const QUEUE_ARRIVAL_RAMP_SECONDS = Number(__ENV.QUEUE_ARRIVAL_RAMP_SECONDS || 10);
export const QUEUE_ARRIVAL_HOLD_SECONDS = Number(__ENV.QUEUE_ARRIVAL_HOLD_SECONDS || 140);

// 시딩된 코호트 크기(seed_queue_flood.sql 의 @cohort_size). 좌석·계정이 이만큼만 있으므로
// iteration 이 이 수를 넘으면 그 뒤는 전부 404 다 — 시나리오가 소진을 세고 회차를 무효로 만든다.
//
// 계단마다 키운다: C = admit x 300s. 소화 시간이 C/admit 이라 계단마다 5분 판정창(15초 스크랩
// x 20표본)이 같게 확보된다. 고정하면 계단이 올라갈수록 회차가 짧아져(admit 160 이면 1분 2초)
// 표본이 4개뿐이라 판정이 안 선다.
export const QUEUE_COHORT_SIZE = Number(__ENV.QUEUE_COHORT_SIZE || 6000);

// arrival-rate 는 VU 를 미리 할당한다. 대기자가 곧 점유 VU 라 유입 종료 시점이 피크다:
//   peak VU ≈ C x (1 - admit/도착률).  기본값(C=6,000 · admit 20 · 도착률 40) → 3,000
//   + SSE 체류 몫 admit x 체류초 = 20 x 10 = 200
// preAllocated 와 max 를 같게 둔다 — 다르면 k6 가 VU 를 지연 생성하고 그 생성 지연 자체가
// dropped_iterations 를 만들어, 이 회차에서 dropped 를 '대상 포화' 와 구분할 수 없게 된다.
export const QUEUE_FLOOD_PRE_ALLOCATED_VUS = Number(__ENV.QUEUE_FLOOD_PRE_ALLOCATED_VUS || 4000);
export const QUEUE_FLOOD_MAX_VUS = Number(__ENV.QUEUE_FLOOD_MAX_VUS || 4000);

// 승급 후 좌석 선택 화면 체류 시간(초). SSE 구독을 이만큼 물고 있다가 닫는다.
//
// 이 값이 SSE 축의 부하를 정하는 다이얼이다 — 동시 구독자 ≈ admit x 체류초, 이벤트율 ≈ admit 이라
// 팬아웃(구독자 x 이벤트율)은 admit 의 제곱으로 큰다. #403 은 구독자 600명 x 5 events/s = 3,000
// sends/s 까지만 검증했고, #540 에서 그 규모에 seat-service RSS 가 602/640 MiB(94.1%)였다.
// 기본 10초면 admit 20 에서 200명, admit 40 에서 400명으로 그 검증 범위 안이다.
//
// ⚠ 계단을 올리면 이 축이 admit 보다 먼저 꺾일 수 있다 — 그것이 이슈 #555 의 두 번째 가설이고,
//   무릎이 잡힌 계단에서 QUEUE_SSE_ENABLED=0 대조 회차 1회로 가른다.
export const QUEUE_SEAT_DWELL_SECONDS = Number(__ENV.QUEUE_SEAT_DWELL_SECONDS || 10);
// 0 이면 SSE 구독을 건너뛴다(대조 회차 전용). 좌석맵과 예매는 그대로 돈다.
export const QUEUE_SSE_ENABLED = (__ENV.QUEUE_SSE_ENABLED || '1') !== '0';

// 유입이 끝나도 대기 중인 사람이 남아 있다. 마지막 순번은 개시로부터 C/admit 초에 승급하므로
// 그때까지 VU 를 끊으면 안 된다 — 끊으면 소화 시간(주 지표)이 측정되지 않는다.
// 기본값 10m = C/admit(300s) 의 2배. 계단마다 확인한다.
export const QUEUE_GRACEFUL_STOP = __ENV.QUEUE_GRACEFUL_STOP || '10m';

// --- 폴링 모델 ---------------------------------------------------------------
// 서버가 next_poll_after_seconds 를 못 준 경우(5xx·타임아웃·본문 파싱 실패)의 폴백. 이 값이 없거나
// 0 이면 k6 가 sleep 없이 재폴링해 스스로 DDoS 가 된다 — 대상이 죽어가는 구간에서 정확히 그렇게
// 되므로, 폴백은 반드시 ADR 0009 §3 의 보수적 T(25초) 이상이어야 한다.
export const QUEUE_FALLBACK_POLL = Number(__ENV.QUEUE_FALLBACK_POLL || 25);

// 클라이언트 지터(±비율). 서버가 주기를 지시해도 1만 명이 같은 순간에 깨어나면 버스트가 그대로
// 남는다. 동기화를 깨는 몫은 클라이언트 책임이다(ADR 0009 §3).
export const QUEUE_JITTER = Number(__ENV.QUEUE_JITTER || 0.2);

// VU 하나가 무한 폴링에 갇히는 것을 막는 안전 상한. 이 값에 걸린 VU 는 queue_polls_exhausted 로
// 집계된다 — 0 이 아니면 입장 허용량이 유입을 소화하지 못한 것이므로 회차 해석의 입력이다.
//
// ⚠ 이 상한은 T 에 종속이다. 직전 값 60 은 T=25초 기준이었는데, 대기 인원이 줄면 T 가 하한 3초까지
// 내려가므로 같은 대기 시간에 폴링이 몇 배 더 필요해진다. R=1,400 을 쓰던 시점 계산으로는 순번
// 9,999 가 106회를 요구했고 순번 6,900 부터 60 을 넘겨, 60 이면 약 31% 가 상한에 걸렸을 것이다.
// 그러면 '클라이언트가 먼저 포기한 것' 이 '입장 허용량이 유입을 못 따라갔다' 로 오독된다.
//
// R 은 이후 400 으로 되돌아갔지만(ADR 0009 §3 — R 은 동시 커넥션의 함수라 저부하 실측값을 쓸 수
// 없다) 이 상한은 300 을 유지한다. #549 B-2 실측이 avg 15.9 / max 39 회라 여유가 크고, 상한이
// 너무 낮을 때의 실패(판정 지표가 거짓 신호를 낸다)가 너무 높을 때의 실패(VU 가 오래 갇힌다)보다
// 나쁘기 때문이다. R 이나 min-poll-seconds 를 바꾸면 이 값도 다시 계산한다.
//
// #555 계단 회차에서 다시 계산했다. 코호트를 C = admit x 300s 로 잡으므로 **어느 계단이든
// 최대 대기가 C/admit = 300초로 같다.** T 는 waiting 에 따라 하한 3초까지 내려가므로 최악이
// 300/3 = 100회다. 300 을 유지한다 — admit 이 바뀌어도 상한이 흔들리지 않는 것이 이 코호트
// 설계의 부수 효과다.
export const QUEUE_MAX_POLLS = Number(__ENV.QUEUE_MAX_POLLS || 300);
