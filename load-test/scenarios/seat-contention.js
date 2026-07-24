// (c) 동일 좌석 경합 — POST /api/v1/booking 을 단 하나의 seat_id 에 집중시킨다(#344).
//
// 이 시나리오는 Redisson 락 경합을 측정하지 못한다. 좌석 홀드는 Kafka 컨슈머 스레드 하나가
// 순차 처리하므로(KafkaConfig에 setConcurrency 없음 → 기본 1) 락은 경합하지 않고
// ticketrush_seat_lock_contention_total 은 0으로 남는다. 실제로 관측되는 차단은 두 군데다.
//   1. booking-service 의 사전 체크(JdbcBookingSeatStatusReader → seat 테이블 직접 SELECT).
//      좌석이 HOLD 로 커밋된 뒤부터 409 를 던진다. → 아래 seat_conflict.
//   2. seat-service 의 isAvailable() → ticketrush_seat_hold_total{result="unavailable"}.
// HOLD 커밋 전 윈도우에 들어온 요청은 전부 201 을 받고 컨슈머로 흘러가 2번에서 걸린다.
// 근거·PromQL·측정 결과는 docs/load-test-guide.md §10. 락 경합 자체는 스레드를 가른
// SeatHoldConcurrencyTest(seat-service)가 검증한다.
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import {
  BASE_URL,
  PERF_ID,
  LOAD_USER_EMAIL,
  LOAD_USER_PASSWORD,
  TARGET_SEAT_ID,
} from '../config/env.js';
import { baseOptions } from '../config/options.js';
import { login } from '../lib/auth.js';

// k6는 2xx/3xx 외를 http_req_failed 로 센다. 이 시나리오에서 409 는 정상 동작이라 그대로 두면
// baseOptions 의 rate<0.01 이 반드시 깨진다. 임계값을 푸는 대신 409 를 기대 응답에 넣어,
// http_req_failed 가 5xx·401·타임아웃은 계속 잡게 남긴다.
// 이 콜백은 파일 스코프가 아니라 해당 run 의 http 모듈 전역이라 import 한 lib/auth.js 의 요청까지
// 덮는다. 그래서 setup() 의 로그인이 쓰는 200 을 빼면 로그인이 실패로 오집계된다.
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export const options = baseOptions;

const seatAccepted = new Rate('seat_accepted'); // 201 = 예매 생성 통과(홀드 확정은 아님)
const seatConflict = new Rate('seat_conflict'); // 409 = 사전 체크 차단 (booking-create.js 와 같은 의미)

export function setup() {
  // 동일 유저가 같은 좌석을 반복 예매해도 booking-service 에 중복 가드가 없고(BookingFacade.createBooking
  // = 참조검증 → 좌석가용검증 → 번호발급 → 생성), booking 테이블에도 unique 가 없다. 계정 1개로 충분하다.
  return { token: login(LOAD_USER_EMAIL, LOAD_USER_PASSWORD) };
}

export default function (data) {
  const res = http.post(
    `${BASE_URL}/api/v1/booking`,
    JSON.stringify({ performance_id: PERF_ID, seat_id: TARGET_SEAT_ID }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` } },
  );

  seatAccepted.add(res.status === 201);
  seatConflict.add(res.status === 409);
  check(res, { 'created(201) or conflict(409)': (r) => r.status === 201 || r.status === 409 });
}
