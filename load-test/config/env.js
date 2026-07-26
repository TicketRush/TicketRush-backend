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
