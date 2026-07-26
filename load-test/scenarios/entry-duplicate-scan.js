// (e) 동일 QR 동시 다중 스캔 — check-in 이 정확히 1건만 성공하는지 확인한다(#402).
//
// 부하 측정이 아니라 정합성 검증이다. 그래서 baseOptions 의 stages/thresholds 를 쓰지 않는다 —
// VU 30개가 1회씩 치고 끝나는 run 에 p(95)<800 같은 임계값은 의미가 없다.
//
// 방어선은 TicketCheckInProcessor 의 조건부 UPDATE 다:
//   UPDATE ticket SET ticket_status='USED' WHERE id=? AND ticket_status='UNUSED'
// 경쟁 패자는 영향행수 0 -> 현재 상태 재조회 -> 409 TICKET_409_002.
//
// k6 에는 배리어가 없으므로 동시성은 두 가지로 확보한다.
//   (a) setup() 에서 QR 을 1개만 뽑아 전 VU 가 같은 토큰을 공유한다. VU 마다 뽑으면 발급 지연이
//       스큐가 되고, 토큰마다 iat/exp 가 달라 '동일 QR' 이라는 전제도 흐려진다.
//   (b) check-in 이 조건부 UPDATE 앞에 booking 동기 왕복(수 ms)을 포함하므로, 경쟁 진입 창이
//       VU 기동 스큐보다 넓다.
// n 을 늘릴 때는 코드로 라운드를 돌리지 않고 -e ENTRY_DUP_BOOKING_ID 를 바꿔 run 을 반복한다
// (라운드 동기화 코드가 0줄이고 매 라운드가 완전 동시가 된다). 런북: docs/load-test-guide.md §12.
//
// 권위 있는 판정은 k6 가 아니라 SQL 이다(가이드 §10.2 (4) oversell 검증과 같은 선).
// k6 Rate 는 run 중 즉시 보이는 1차 확인용이다 — 요약에 "✓ 1 ✗ 29" 로 절대건수까지 찍힌다.
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import {
  BASE_URL,
  LOAD_ADMIN_EMAIL,
  LOAD_ADMIN_PASSWORD,
  ENTRY_DUP_BOOKING_ID,
  ENTRY_DUP_VUS,
} from '../config/env.js';
import { login } from '../lib/auth.js';

// 200 포함 필수 — 콜백이 run 전역이라 lib/auth.js 의 로그인까지 덮는다(entry-spike.js 와 같은 이유).
http.setResponseCallback(http.expectedStatuses(200, 409));

export const options = {
  scenarios: {
    duplicate_scan: {
      executor: 'per-vu-iterations',
      vus: ENTRY_DUP_VUS,
      iterations: 1,
      maxDuration: '1m',
    },
  },
};

const checkinOk = new Rate('dup_checkin_ok'); // 절대건수가 정확히 1이어야 한다
const alreadyUsed = new Rate('dup_already_used'); // 나머지 N-1
const unexpected = new Rate('dup_unexpected'); // 0 이어야 한다

export function setup() {
  const token = login(LOAD_ADMIN_EMAIL, LOAD_ADMIN_PASSWORD);

  const qr = http.get(`${BASE_URL}/api/v1/ticket/bookings/${ENTRY_DUP_BOOKING_ID}/qr`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const payload = qr.json('result.payload');
  if (!payload) {
    throw new Error(
      `QR 발급 실패 status=${qr.status} bookingId=${ENTRY_DUP_BOOKING_ID}. ` +
        `seed_entry.sql 로 해당 티켓이 UNUSED 로 시딩됐는지 확인.`,
    );
  }
  return { token, payload };
}

export default function (data) {
  const res = http.post(
    `${BASE_URL}/api/v1/entries/check-in`,
    JSON.stringify({ token: data.payload }),
    {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` },
      tags: { name: 'entry_checkin_dup' },
    },
  );

  const code = res.status === 200 ? null : res.json('code');
  checkinOk.add(res.status === 200);
  alreadyUsed.add(res.status === 409 && code === 'TICKET_409_002');
  unexpected.add(!(res.status === 200 || code === 'TICKET_409_002'));

  check(res, {
    'checked in(200) or already used(409)': (r) => r.status === 200 || code === 'TICKET_409_002',
  });
}
