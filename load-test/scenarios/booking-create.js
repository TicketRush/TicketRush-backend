// (a) 예매 생성 핫패스 — POST /api/v1/booking (인증). 좌석 락/HOLD는 이 요청이 발행한
// Kafka 이벤트로 비동기 처리되므로 HTTP 응답은 대부분 201이고, 경합은 seat-service 메트릭에서 드러난다.
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import {
  BASE_URL,
  PERF_ID,
  LOAD_USER_EMAIL,
  LOAD_USER_PASSWORD,
  SEAT_ID_MIN,
  SEAT_ID_MAX,
} from '../config/env.js';
import { baseOptions } from '../config/options.js';
import { login } from '../lib/auth.js';

export const options = baseOptions;

// 좌석 경합/선점불가(4xx)는 부하 결과에선 실패가 아니라 별도 관측치.
const seatConflict = new Rate('seat_conflict');

export function setup() {
  return { token: login(LOAD_USER_EMAIL, LOAD_USER_PASSWORD) };
}

export default function (data) {
  // seat_id를 VU/반복으로 분산해 특정 좌석 집중·고갈을 완화(ponytail: 단순 분산이라 완전 유일성은 아님).
  const span = SEAT_ID_MAX - SEAT_ID_MIN + 1;
  const seatId = SEAT_ID_MIN + ((__VU * 1000 + __ITER) % span);

  const res = http.post(
    `${BASE_URL}/api/v1/booking`,
    JSON.stringify({ performance_id: PERF_ID, seat_id: seatId }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` } },
  );

  seatConflict.add(res.status === 409 || res.status === 400);
  check(res, {
    'booking created(201) or conflict': (r) =>
      r.status === 201 || r.status === 409 || r.status === 400,
  });
}
