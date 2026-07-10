// k6 시나리오 공통 환경변수 파싱 + 기본값. 실행 시 `-e KEY=VALUE` 또는 K6_* 로 덮는다.
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080'; // 게이트웨이

export const PERF_ID = Number(__ENV.PERF_ID || 1); // 시딩한 공연 ID

// 부하테스트 전용 계정(seed_load.sql 이 시딩한다). 비밀번호는 평문을 커밋하지 않으므로 실행 인자로 준다.
export const LOAD_USER_EMAIL = __ENV.LOAD_USER_EMAIL || 'loadtest@ticketrush.local';
export const LOAD_USER_PASSWORD = __ENV.LOAD_USER_PASSWORD || '';

// booking 시나리오가 예매 시도할 seat_id 범위(시딩 규모에 맞춰 넓게 잡아 좌석 고갈 완화)
export const SEAT_ID_MIN = Number(__ENV.SEAT_ID_MIN || 1);
export const SEAT_ID_MAX = Number(__ENV.SEAT_ID_MAX || 100);

// 부하 프로파일(램프업)
export const VUS = Number(__ENV.VUS || 50);
export const RAMP = __ENV.RAMP || '30s';
export const STEADY = __ENV.STEADY || '1m';
