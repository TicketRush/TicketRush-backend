// (c) 동일 좌석 경합 — POST /api/v1/booking 을 단 하나의 seat_id 에 집중시킨다(#344).
//
// 이 시나리오가 락 경합을 측정할 수 있는지는 spring.kafka.listener.concurrency 에 달려 있다(#596).
// 1이면 컨슈머 스레드 하나가 순차 처리하고 RLock 이 (clientUUID:threadId) 기준 재진입이라
// tryLock 이 실패하지 않아 ticketrush_seat_lock_contention_total 이 0으로 남는다.
// 현행 기본값은 3이고, #598 이 같은 배포본에서 1↔3 을 토글해 실측했다 —
// 1에서 0(시리즈 미생성) / 3에서 3,621. 회차마다 실제 값을 metadata.txt 에 적는다.
// ⚠ 어느 쪽이든 오버셀은 0이다. 정합성을 지키는 것은 컨슈머 직렬화가 아니라
//   Seat.@Version(#427) + isAvailable() 이다 — #598 이 실제 경합 3,621건으로 확인했다.
//
// 이 POST 와 좌석 HOLD 사이에는 비동기 구간이 둘이다. #471 로 booking 발행이 outbox 모드가 되어
// 요청은 outbox 행만 커밋하고 끝나고, OutboxRelayScheduler(fixedDelay 5s)가 그걸 5초마다
// batch-size 만큼 꺼내 발행한다 → 발행 상한 = batch-size / 5초 (현행 300 → ≈60 events/s, #489).
// 즉 여기서 재는 RPS·p99 는 유입 축이고, 홀드까지의 지연은 outbox backlog·컨슈머 랙(처리 축)으로
// 봐야 한다. 이 상한 때문에 이 시나리오로 컨슈머 처리량을 판정할 수 없다 —
// #598 은 그래서 이벤트를 토픽에 직접 주입하는 별도 시나리오(#504 절차)를 주 판정축으로 썼다.
//
// 실제로 관측되는 차단은 두 군데다.
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
