// (k) payment→booking 동기 조회 왕복 실측 — POST /api/v1/payment/confirm (#633).
//
// #571 서킷브레이커의 임계값 5종을 정하려면 이 왕복의 지연 분포가 필요한데, 그 값을 재는 축이
// 지금까지 없었다. 이 회차가 만드는 축은 payment 의 Timer 다:
//   ticketrush_payment_booking_lookup_seconds{outcome="success"}  ← 왕복 SSOT (p50/p95/p99/max)
//   http_server_requests_seconds{uri="/api/v1/internal/booking/{bookingId}"} ← booking 자체 처리(하한)
//   둘의 차이 = 컨테이너 네트워크 + 클라이언트 오버헤드 (#402 는 이 값이 1.6ms 였다)
//
// ── PG 에 도달하지 않는 confirm ─────────────────────────────────────────────
// PaymentConfirmUseCase 는 ①payment COMPLETED 조회 → ②expired_booking 조회 → ③booking 왕복 →
// ④PG 승인 순서다. 코호트를 CONFIRMED 로 심어 두면 ③에서 BOOKING_409_002 로 끊기고 ④로
// 내려가지 않는다. 그래서 **실 PG 호출 0건**으로 실경로 왕복을 반복시킬 수 있다.
// 그 차단은 recordFailedPayment 의 try 블록 밖이라 FAILED 이력도 남지 않고, saveAndFlush 는
// 그보다 뒤에 있어 DB 쓰기가 0이다 — 코호트가 소모되지 않으므로 회차를 몇 번이든 반복할 수 있다.
//
// ── 방어선: 예방 2겹 + 탐지 3겹 ───────────────────────────────────────────
// 시딩이 잘못돼 코호트에 PENDING 이 섞이면 그 요청은 ④까지 내려간다. 예방과 탐지를 구분해 둔다 —
// 킬 스위치는 예방이 아니다(가이드 §17.3 과 같은 구분).
//
// 예방(④ 도달 자체를 막는다)
//   1. 시드 verify 의 pending = 0 단언 (seed_payment_booking_roundtrip.sql)
//   2. provider 를 KAKAO 로 보낸다. **prod 한정으로** 실 승인 구현체가 Toss 뿐이라
//      (PaymentKeyFormat javadoc) 라우터가 PAYMENT_PROVIDER_NOT_SUPPORTED 로 선차단하고,
//      그 ErrorStatus 는 RECORDABLE_FAILURES 화이트리스트(#297)에 없어 FAILED 이력조차 남지 않는다.
//      ⚠ local/test 는 다르다. StubPaymentApprovalClient 가 @Profile("!prod") + stub.enabled=true
//        조건의 fallback 빈이라, 스텁이 켜져 있으면 라우터가 KAKAO 를 그 스텁으로 보내 **승인이
//        성공하고 COMPLETED payment 행 + PaymentConfirmedEvent 까지 나간다.**
//
// 탐지(일어난 뒤에 잡는다)
//   3. 회차 전 수동 curl 1건으로 BOOKING_409_002 확인 (가이드 §17.3)
//   4. 아래 킬 스위치. 기대 code 가 아니면 test.abort() 한다. **첫 이상 응답을 보고 중단하기까지
//      이미 여러 요청이 in-flight 이므로 "④ 도달 0건" 을 보장하지는 못한다.**
//   5. 회차 후 pg_approve Timer 증분 확인 (가이드 §17.6)
//
// ── 회차를 두 run 으로 나누는 이유 ─────────────────────────────────────────
// 워밍업 구간(RT_PROFILE=warmup)은 payment·booking 재시작 **직후** 부하가 시작돼야 한다.
// 한 run 안에 재시작을 끼우면 그 사이 요청이 전부 통신 실패라 워밍업 지연이 아니라 재기동 공백을
// 재게 된다. 그래서 warmup 을 앞뒤로 두 번 따로 돌린다(W1/W2) — JIT 는 비결정적이라 1회 관측으로
// 임계를 잡으면 #496 이 밟은 함정을 반복한다.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import {
  BASE_URL,
  PERF_ID,
  LOAD_USER_EMAIL,
  LOAD_USER_PASSWORD,
  SEAT_ID_MIN,
  SEAT_ID_MAX,
  RT_PROFILE,
  RT_BOOKING_ID_MIN,
  RT_SEAT_ID_MIN,
  RT_COHORT_SIZE,
  RT_INDEX_OFFSET,
  RT_BASELINE_RATE,
  RT_PEAK_RATE,
  RT_BASELINE_DURATION,
  RT_PEAK_DURATION,
  RT_RAMP,
  RT_WARMUP_DURATION,
  RT_BACKGROUND_RATE,
  RT_PRE_ALLOCATED_VUS,
  RT_MAX_VUS,
} from '../config/env.js';
import { baseOptions } from '../config/options.js';
import { login } from '../lib/auth.js';
import { jsonField } from '../lib/json.js';

// 409 는 이 회차의 **정상 응답**이다(측정군은 전건 409 로 끝난다). 200 을 반드시 함께 넣는다 —
// 이 콜백은 run 전역이라 lib/auth.js 의 로그인(200)까지 덮는다.
// confirm 의 200 은 사고지만 그 판정은 이 콜백이 아니라 아래 킬 스위치가 한다. 상태코드 기대에서
// 200 을 빼면 로그인이 실패로 잡혀 http_req_failed 가 회차 내내 오염된다.
// 배경 부하(POST /api/v1/booking)의 201·409·400 도 정상 범위라 함께 넣는다.
http.setResponseCallback(http.expectedStatuses(200, 201, 400, 409));

const CONFIRM_TAG = { name: 'payment_confirm' };
const BACKGROUND_TAG = { name: 'rt_background_booking' };

// 측정군이 반드시 받아야 하는 응답. 이 code 로 끝나야 ③의 왕복이 실제로 일어난 것이다.
const EXPECTED_CODE = 'BOOKING_409_002';

function buildScenarios() {
  const scenarios = {};

  if (RT_PROFILE === 'warmup') {
    // 재기동 직후 구간. 도착률을 정상값으로 고정한다 — 실제 배포 직후에도 평시 트래픽이 그대로
    // 들어오므로, 낮춰 잡으면 워밍업 부담을 과소평가한다.
    scenarios.rt_warmup = {
      executor: 'constant-arrival-rate',
      rate: RT_BASELINE_RATE,
      timeUnit: '1s',
      duration: RT_WARMUP_DURATION,
      preAllocatedVUs: RT_PRE_ALLOCATED_VUS,
      maxVUs: RT_MAX_VUS,
      exec: 'confirmRoundtrip',
    };
  } else {
    scenarios.rt_main = {
      executor: 'ramping-arrival-rate',
      timeUnit: '1s',
      startRate: RT_BASELINE_RATE,
      preAllocatedVUs: RT_PRE_ALLOCATED_VUS,
      maxVUs: RT_MAX_VUS,
      exec: 'confirmRoundtrip',
      stages: [
        { target: RT_BASELINE_RATE, duration: RT_BASELINE_DURATION }, // B1 정상
        { target: RT_PEAK_RATE, duration: RT_RAMP }, // 램프업
        { target: RT_PEAK_RATE, duration: RT_PEAK_DURATION }, // B2 피크
      ],
    };
  }

  // 배경 부하(회차 2 전용). booking-service 를 예매 트래픽으로 바쁘게 만든 상태에서 왕복을 잰다.
  // 회차 1(단독)과의 차이가 곧 혼잡도 기여분이다.
  //
  // ⚠ duration 을 넉넉히 주고 gracefulStop 으로 정리하면 안 된다. k6 의 테스트 종료 시점은
  //   **가장 오래 도는 시나리오**가 정하고, gracefulStop 은 그 시나리오 자신의 duration 이 끝난 뒤
  //   진행 중 iteration 을 얼마나 기다릴지만 정한다. 측정이 끝난 뒤에도 배포본에 예매 생성 부하가
  //   계속 흐르고, §7 규약으로 남기는 run 시작/종료 UTC 가 실제 측정 창과 어긋나며,
  //   "회차 1 vs 회차 2 차이 = 혼잡도 기여분" 이라는 해석의 전제(두 회차의 관측 창이 같다)도 깨진다.
  //   그래서 측정 시나리오와 **같은 stages** 를 쓰되 target 만 배경 도착률로 고정한다.
  if (RT_BACKGROUND_RATE > 0) {
    scenarios.rt_background =
      RT_PROFILE === 'warmup'
        ? {
            executor: 'constant-arrival-rate',
            rate: RT_BACKGROUND_RATE,
            timeUnit: '1s',
            duration: RT_WARMUP_DURATION,
            preAllocatedVUs: RT_PRE_ALLOCATED_VUS,
            maxVUs: RT_MAX_VUS,
            exec: 'backgroundBooking',
          }
        : {
            executor: 'ramping-arrival-rate',
            timeUnit: '1s',
            startRate: RT_BACKGROUND_RATE,
            preAllocatedVUs: RT_PRE_ALLOCATED_VUS,
            maxVUs: RT_MAX_VUS,
            exec: 'backgroundBooking',
            // 측정 시나리오와 구간 길이가 정확히 같다. 배경 부하는 계단을 타지 않고 평평하게 흐른다
            // — 측정군의 정상/피크 대비가 배경 혼잡도 변화에 오염되지 않아야 한다.
            stages: [
              { target: RT_BACKGROUND_RATE, duration: RT_BASELINE_DURATION },
              { target: RT_BACKGROUND_RATE, duration: RT_RAMP },
              { target: RT_BACKGROUND_RATE, duration: RT_PEAK_DURATION },
            ],
          };
  }

  return scenarios;
}

export const options = {
  scenarios: buildScenarios(),
  // 값은 재정의하지 않는다(기존 관례). 이 회차에서 p(95)<800 이 깨지면 그 자체가 관측 대상이다.
  thresholds: baseOptions.thresholds,
};

// 클라이언트 관점 지연. **왕복의 SSOT 가 아니다** — confirm 응답 전체(게이트웨이 1홉 + JWT +
// DB 조회 2회 + 왕복)라 왕복만 분리되지 않고, 가정용 회선 왕복도 섞인다(ADR 0004).
// 왕복 수치는 payment 의 Timer 에서 읽고, 이 Trend 는 체감 지연·이상 구간 탐지용 보조축이다.
const confirmDuration = new Trend('rt_confirm_duration', true);

// 측정군이 기대대로 ③에서 끊겼는가. 1.0 이 아니면 그 회차는 무효다.
const guardBlocked = new Rate('rt_guard_blocked');
// booking 통신 실패(503). 정상 회차에서는 0 이어야 하고, 0 이 아니면 왕복 분포에
// outcome=failed 가 섞였다는 뜻이라 해석 시 분리해야 한다.
const bookingUnavailable = new Rate('rt_booking_unavailable');

// 배경 부하가 실제로 booking-service 에 닿았는가(회차 2). 1.0 이 아니면 그 회차의
// "혼잡한 상태에서 잰 값" 이라는 전제가 성립하지 않는다.
// ⚠ 특히 QUEUE_ENABLED=true 이면 입장 토큰 없는 POST /api/v1/booking 은 게이트웨이에서
//   전건 403 이라 booking-service 에 도달조차 하지 않는다(gateway application.yml §queue).
//   그 경우 혼잡도는 0 인데 회차는 정상으로 보인다.
const backgroundOk = new Rate('rt_background_ok');

export function setup() {
  // 코호트 소유자가 이 계정이라(seed_payment_booking_roundtrip.sql) #572 소유자 대조를 통과한다.
  // 로그인은 여기서 1회만 — VU 마다 로그인하면 auth 가 병목처럼 왜곡된다.
  return { token: login(LOAD_USER_EMAIL, LOAD_USER_PASSWORD) };
}

export function confirmRoundtrip(data) {
  // arrival-rate 는 VU 를 풀에서 재사용하므로 __VU/__ITER 조합은 희소·비단조다. 코호트를 고르게
  // 돌려면 시나리오 전역 단조 카운터를 써야 한다(entry-spike.js 와 같은 이유).
  // 오프셋을 더하는 것은 run 간 편향을 깨기 위해서다 — 이 카운터는 run 마다 0 부터 시작하므로
  // 오프셋이 없으면 W1·B·W2 가 코호트의 같은 머리만 반복해 읽고, W2 가 W1 이 데운 버퍼 풀의
  // 이득을 그대로 받는다(env.js RT_INDEX_OFFSET 주석 참고).
  const idx = (RT_INDEX_OFFSET + exec.scenario.iterationInTest) % RT_COHORT_SIZE;
  const bookingId = RT_BOOKING_ID_MIN + idx;
  const seatId = RT_SEAT_ID_MIN + idx;

  const res = http.post(
    `${BASE_URL}/api/v1/payment/confirm`,
    JSON.stringify({
      booking_id: bookingId,
      seat_id: seatId,
      // ⚠ KAKAO 는 의도적이다. prod 의 실 승인 구현체는 Toss 뿐이라, 시딩 사고로 ④에 도달해도
      //   라우터가 PAYMENT_PROVIDER_NOT_SUPPORTED 로 끊어 실제 과금이 일어나지 않는다.
      provider: 'KAKAO',
      amount: 50000,
      // PaymentKeyFormat: 인쇄가능 ASCII 1~200자. ③에서 끊기므로 PG 로 나가지 않지만,
      // 형식이 어긋나면 컨트롤러 @Valid 가 400 으로 끊어 왕복 자체가 일어나지 않는다.
      payment_key: `LTR-${bookingId}`,
    }),
    {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` },
      tags: CONFIRM_TAG,
    },
  );

  confirmDuration.add(res.timings.duration);

  const code = jsonField(res, 'code');
  guardBlocked.add(code === EXPECTED_CODE);
  bookingUnavailable.add(res.status === 503);

  // ── 킬 스위치 ────────────────────────────────────────────────────────────
  // 200 은 PG 승인이 성사됐다는 뜻이다. 이 회차에서 그것은 측정 실패가 아니라 **사고**다.
  // 503 은 abort 하지 않는다 — booking 이 실제로 느려지거나 죽은 구간은 관측 대상이고,
  // 그 판정은 회차 종료 후 rt_booking_unavailable 로 한다(0 이 아니면 무효).
  if (res.status === 200) {
    exec.test.abort(
      `[중단] confirm 이 200 을 반환했다 — PG 승인이 실행됐다는 뜻이다. ` +
        `코호트에 PENDING 이 섞였는지 시드 verify(pending=0)를 즉시 확인할 것. bookingId=${bookingId}`,
    );
  }
  if (res.status !== 503 && code !== EXPECTED_CODE) {
    // 왕복이 일어나지 않았거나 다른 단계에서 끊긴 요청이다. 그대로 두면 Timer 표본이 요청 수보다
    // 적은 채로 회차가 끝나고, 그 사실이 리포트 단계에서야 드러난다.
    //   PAYMENT_409_001 → ①에서 끊김(코호트에 payment 행이 있다)
    //   BOOKING_409_003 → ②에서 끊김(expired_booking 에 있다)
    //   404            → 소유자 불일치·예매 없음(왕복은 있었으나 outcome=not_found 로 갈린다)
    //   400            → 요청 본문 검증에서 끊김(왕복 없음)
    exec.test.abort(
      `[중단] 기대하지 않은 응답이다. status=${res.status}, code=${code}, ` +
        `bookingId=${bookingId} — 기대는 409 ${EXPECTED_CODE} 다. 시딩을 확인할 것.`,
    );
  }

  check(res, { 'guard blocked(409 BOOKING_409_002)': (r) => r.status === 409 });
}

// 배경 부하. booking-service 에 예매 생성 트래픽을 흘려 혼잡한 상태를 만든다.
// 초반에는 201 로 실제 HOLD 를 만들며 좌석 대역을 소모하고, **고갈된 뒤부터** 대부분이 409(선점
// 반려)가 된다(TTL 만료분이 다시 잡히는 몫도 섞인다). 어느 쪽이든 요청이 booking 을 거치므로
// 혼잡도는 회차 내내 유지되지만, **이 부하가 §13.3 코호트의 좌석 상태를 실제로 바꾼다**는 점은
// 회차 2 를 돌린 뒤 그 코호트를 쓰는 다른 회차가 있다면 고려해야 한다.
export function backgroundBooking(data) {
  const span = SEAT_ID_MAX - SEAT_ID_MIN + 1;
  const seatId = SEAT_ID_MIN + (exec.scenario.iterationInTest % span);

  const res = http.post(
    `${BASE_URL}/api/v1/booking`,
    JSON.stringify({ performance_id: PERF_ID, seat_id: seatId }),
    {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` },
      tags: BACKGROUND_TAG,
    },
  );

  // 201(생성) · 409/400(좌석 선점 반려) 이 정상 범위다. 그 밖은 배경 부하가 booking 에 닿지
  // 못했다는 뜻이라 회차 2 의 전제가 깨진다 — 401(토큰), 403(대기열 활성), 404(좌석 대역 오설정), 5xx.
  // abort 하지 않는 것은 이 축이 측정군이 아니기 때문이다. 판정은 회차 종료 후 rt_background_ok 로 한다.
  backgroundOk.add(res.status === 201 || res.status === 409 || res.status === 400);
  check(res, {
    'background booking reached(201/409/400)': (r) =>
      r.status === 201 || r.status === 409 || r.status === 400,
  });
}
