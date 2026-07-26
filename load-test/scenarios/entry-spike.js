// (d) 입장 검표 스파이크 — QR 발급 -> POST /api/v1/entries/verify -> check-in (#402).
//
// ── 기존 관례를 깨는 지점 3개와 그 이유 ──────────────────────────────────────
// 1. executor 가 ramping-vus(config/options.js 의 baseOptions.stages) 가 아니라 ramping-arrival-rate 다.
//    check-in 은 티켓을 UNUSED -> USED 로 비가역 소모한다. VU 수를 통제하면 소요 티켓 수가 응답
//    지연에 반비례해 변해서 시딩 규모를 미리 정할 수 없다(느려지면 덜 쓰고, 빨라지면 고갈된다).
//    도착률을 통제하면 소요량이 stages 만으로 계산된다. 실제 입장 게이트의 부하도 스캐너 수가
//    아니라 관객 도착률이다.
// 2. 커스텀 Trend 를 쓴다(기존 시나리오는 Rate 만 썼다). "booking 동기 왕복이 검표 지연에 미치는
//    영향을 수치로 분리" 하려면 엔드포인트별 지연이 증적 파일 k6-summary.txt 에 남아야 하는데,
//    k6 기본 요약은 http_req_duration 을 태그별로 쪼개주지 않는다.
// 3. 모든 요청에 tags:{name:...} 을 준다. 선택이 아니라 필수다 — QR URL 에 bookingId 가 들어가서
//    태그를 안 주면 k6 가 URL 을 그대로 name 라벨로 써서 Prometheus 에 티켓 수만큼 시계열이 생긴다.
//
// iteration 1회 = QR 발급 -> verify -> check-in (요청 3개, 티켓 1장 소모).
// QR 을 setup() 에서 미리 뽑지 않는 이유: ticket.qr.ttl-millis 가 5분인데 이 run 은 15분 40초다.
// 미리 뽑으면 5분째부터 전 요청이 401 TICKET_401_001 로 뒤집혀 스파이크 구간을 통째로 잃는다.
// 발급을 체인에 두면 TTL 이 구조적으로 무관해지고, 덤으로 booking 왕복이 '없는' 통제군이 생긴다
// (TicketQrGetUseCase 는 로컬 DB 만 읽는다 — #364).
//
// 이 시나리오가 측정하지 못하는 것: ticket-service 의 booking 호출 클라이언트 관점 지연.
// RestClientConfig 가 오토컨피그된 RestClient.Builder 빈이 아니라 생 RestClient.builder() 를 쓰므로
// ObservationRegistry 가 붙지 않아 http_client_requests_seconds 자체가 없다. 그래서 왕복 비용은
// (verify - qr) 지연 차분(상한)과 booking-service 서버 메트릭(하한)으로 협공한다. 근거·PromQL 은
// docs/load-test-guide.md §12.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import {
  BASE_URL,
  LOAD_ADMIN_EMAIL,
  LOAD_ADMIN_PASSWORD,
  ENTRY_BOOKING_ID_MIN,
  ENTRY_TICKET_COUNT,
  ENTRY_BASELINE_RATE,
  ENTRY_PEAK_RATE,
  ENTRY_BASELINE_DURATION,
  ENTRY_PEAK_DURATION,
  ENTRY_SPIKE_RAMP,
  ENTRY_PRE_ALLOCATED_VUS,
  ENTRY_MAX_VUS,
} from '../config/env.js';
import { baseOptions } from '../config/options.js';
import { login } from '../lib/auth.js';

// 409 는 정상 차단(중복 스캔)이라 실패가 아니다 — seat-contention.js 와 같은 선(가이드 §10.4).
// 200 을 반드시 함께 넣는다: 이 콜백은 파일 스코프가 아니라 run 전역이라 lib/auth.js 의 로그인(200)까지 덮는다.
// 401(만료 QR)·404(티켓 없음)·503 은 일부러 뺐다. 이 시나리오에서 그것들은 전부 시딩/인덱싱 결함
// 이거나 진짜 장애다 — http_req_failed 가 계속 잡아야 한다.
http.setResponseCallback(http.expectedStatuses(200, 409));

export const options = {
  scenarios: {
    entry_spike: {
      executor: 'ramping-arrival-rate',
      timeUnit: '1s',
      startRate: ENTRY_BASELINE_RATE,
      preAllocatedVUs: ENTRY_PRE_ALLOCATED_VUS,
      maxVUs: ENTRY_MAX_VUS,
      stages: [
        { target: ENTRY_BASELINE_RATE, duration: ENTRY_BASELINE_DURATION }, // baseline (>=5분)
        { target: ENTRY_PEAK_RATE, duration: ENTRY_SPIKE_RAMP }, // 스파이크 램프업
        { target: ENTRY_PEAK_RATE, duration: ENTRY_PEAK_DURATION }, // 피크 유지
        { target: ENTRY_BASELINE_RATE, duration: ENTRY_SPIKE_RAMP }, // 램프다운
        { target: ENTRY_BASELINE_RATE, duration: ENTRY_BASELINE_DURATION }, // 회복 (>=5분)
      ],
    },
  },
  // scenarios 를 쓰면 top-level stages 를 못 쓰므로 공통 옵션에서 thresholds 만 가져온다.
  // 값은 재정의하지 않는다(기존 관례) — 스파이크에서 p(95)<800 이 깨지는 것 자체가 관측 대상이다.
  thresholds: baseOptions.thresholds,
};

const qrDuration = new Trend('entry_qr_duration', true); // booking 왕복 없음 (통제군)
const verifyDuration = new Trend('entry_verify_duration', true); // booking 왕복 있음
const checkinDuration = new Trend('entry_checkin_duration', true); // booking 왕복 + 조건부 UPDATE

const checkinOk = new Rate('entry_checkin_ok'); // 200 = 입장 처리 성공
const alreadyUsed = new Rate('entry_already_used'); // 409 TICKET_409_002 = 설계된 정상 차단
const notUsable = new Rate('entry_not_usable'); // 409 TICKET_409_001 = 시딩 결함 신호, 0 이어야 한다
const bookingDown = new Rate('entry_booking_unavailable'); // 503 TICKET_503_001 = 왕복 실패
const ticketExhausted = new Rate('entry_ticket_exhausted'); // >0 이면 이 회차는 무효

export function setup() {
  // 티켓 소유자 == 이 ADMIN 계정이라(seed_entry.sql) QR 발급의 소유자 검사와
  // verify/check-in 의 hasRole('ADMIN')이 토큰 하나로 전부 통과한다. 로그인은 여기서 1회만.
  return { token: login(LOAD_ADMIN_EMAIL, LOAD_ADMIN_PASSWORD) };
}

export default function (data) {
  // exec.scenario.iterationInTest = 이 시나리오의 전역 0-base 단조 카운터.
  // booking-create.js 의 __VU*1000+__ITER 은 여기서 쓰면 안 된다. arrival-rate 는 VU 를 풀에서
  // 재사용해 (VU=2,ITER=0) 과 (VU=1,ITER=1000) 이 충돌하고, 무엇보다 값이 희소·비단조라
  // 필요 티켓 수를 계산할 수 없다. 인덱스가 겹치면 이미 USED 인 티켓을 다시 쳐서 409 가 나는데,
  // 그게 '정상 차단' 으로 위장되어 측정이 조용히 오염된다.
  const idx = exec.scenario.iterationInTest;
  ticketExhausted.add(idx >= ENTRY_TICKET_COUNT);
  if (idx >= ENTRY_TICKET_COUNT) {
    // 티켓 고갈. exec.test.abort() 를 쓰지 않는다 — 회복 구간(완료조건 3) 도중에 abort 하면
    // 회차 전체를 버려야 한다. 요약에서 entry_ticket_exhausted > 0 이면 그 회차를 무효 처리한다.
    return;
  }
  const bookingId = ENTRY_BOOKING_ID_MIN + idx;

  const auth = {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` },
  };

  // (1) QR 발급 — booking-service 호출 없음. verify 와의 차이가 곧 동기 왕복 비용이다.
  const qr = http.get(`${BASE_URL}/api/v1/ticket/bookings/${bookingId}/qr`, {
    ...auth,
    tags: { name: 'entry_qr' },
  });
  qrDuration.add(qr.timings.duration);

  const token = jsonField(qr, 'result.payload');
  if (!token) {
    // 여기서 실패하면 시딩이 잘못됐거나(소유자 불일치·티켓 없음 404) 앱이 포화된 것이다.
    check(qr, { 'qr issued(200)': (r) => r.status === 200 });
    return;
  }
  const body = JSON.stringify({ token });

  // (2) verify — 상태를 바꾸지 않는다. QR 발급 대비 delta = booking 왕복 + booking-service 처리.
  const verify = http.post(`${BASE_URL}/api/v1/entries/verify`, body, {
    ...auth,
    tags: { name: 'entry_verify' },
  });
  verifyDuration.add(verify.timings.duration);
  recordCodes(verify);

  // (3) check-in — verify 와 같은 검증(booking 왕복 포함)을 한 번 더 돈 뒤 조건부 UPDATE.
  const checkin = http.post(`${BASE_URL}/api/v1/entries/check-in`, body, {
    ...auth,
    tags: { name: 'entry_checkin' },
  });
  checkinDuration.add(checkin.timings.duration);
  checkinOk.add(checkin.status === 200);
  recordCodes(checkin);

  check(checkin, { 'checked in(200)': (r) => r.status === 200 });
}

// 409 를 기대응답에 넣으면 '중복 스캔(TICKET_409_002)' 과 '예매 미확정(TICKET_409_001)' 이 둘 다
// 성공으로 집계된다. 상태코드로는 못 가르므로 본문 code 로 가른다
// (ApiResponse.code 는 최상위 String 필드라 전역 snake_case 전략의 영향을 받지 않는다).
// 200 은 파싱하지 않는다 — 피크에서 초당 180건을 JSON 파싱하면 생성기가 병목이 된다.
function recordCodes(res) {
  if (res.status === 200) {
    alreadyUsed.add(false);
    notUsable.add(false);
    bookingDown.add(false);
    return;
  }
  const code = jsonField(res, 'code');
  alreadyUsed.add(code === 'TICKET_409_002');
  notUsable.add(code === 'TICKET_409_001');
  bookingDown.add(code === 'TICKET_503_001');
}

// res.json() 은 본문이 없거나 JSON 이 아니면 예외를 던지고, 그러면 iteration 이 통째로 죽는다.
// 타임아웃·연결 실패는 status=0 / body=null 로 오는데 그건 스파이크 피크에서 실제로 나올 수 있는
// 상황이다. 하필 포화를 관측해야 할 구간에서 지표가 비뚤어지므로 파싱 실패를 삼킨다.
// 이렇게 삼켜도 그 요청은 http_req_failed 에 그대로 남아 에러율에서 빠지지 않는다.
function jsonField(res, path) {
  if (!res.body) {
    return null;
  }
  try {
    return res.json(path);
  } catch (e) {
    return null;
  }
}
