// (h) 좌석 상태 SSE 대량 구독 — 팬아웃 전파 지연·수신 누락 (#403).
//
// ⚠ 이 시나리오는 k6-sse 이미지에서만 돈다(load-test/Dockerfile.k6-sse).
//    docker compose run --rm k6-sse run /scripts/scenarios/seat-sse-fanout.js
//    기본 grafana/k6 로 실행하면 'k6/x/sse' 해석 실패로 즉시 죽는다.
//
// ── 이슈 서사와 실제 구조의 차이 ────────────────────────────────────────────
// 이슈는 "queue(1000) 적체 -> max(16)까지 스레드 증가" 로 포화를 서술하지만, 큐에 쌓이는 것은
// 구독자가 아니라 '이벤트' 다. SeatStatusSseEventSender.send() 는 이벤트 1건당 executor 태스크
// 1개를 던지고, 그 태스크 하나가 구독자 전원에게 순차로 emitter.send() 한다
// (SeatStatusSseEventSender.java:40-54). 즉 구독자 N 명은 스레드 수요를 늘리는 것이 아니라
// '태스크 1개의 길이' 를 N 배로 늘린다. 포화 조건은 (이벤트율 x N x send시간) > core 4 다.
// 그래서 이 시나리오는 이벤트율을 고정하고 구독자 수만 계단으로 올린다.
//
// ── 전파 지연을 어떻게 재는가 ───────────────────────────────────────────────
// SeatStatusChangedResponse 에 발생 시각 필드가 없고(performance_id, seat_id, seat_layout_id,
// seat_number, seat_status, hold_expired_at), EC2 와 로컬 k6 는 다른 호스트다. 서버가 찍은
// 시각과 클라이언트 수신 시각을 빼면 두 호스트의 시계 차가 그대로 섞인다.
// 그래서 probe VU 가 '자기가 예매를 걸고 자기 seat_id 이벤트가 돌아오는' 시각차를 잰다 —
// 시작과 끝이 같은 k6 프로세스의 같은 시계라 시계 차를 타지 않는다.
// probe 는 매 iteration 마다 새로 구독하므로 CopyOnWriteArrayList 의 뒤쪽에 등록된다
// = 팬아웃 전 구간을 통과한 뒤에 받는 값(최악값)이다. 리포트에 이 성질을 명시할 것.
//
// ── 이 값에 무엇이 포함되는가 ───────────────────────────────────────────────
// sse_propagation_ms = 예매 요청 송신 -> 이벤트 수신. 즉 booking API + Kafka + seat HOLD 트랜잭션
// + SSE 팬아웃 전부다. 팬아웃 몫만 떼려면 같은 iteration 의 sse_probe_booking_duration 을 뺀다
// (그래도 Kafka 구간은 남는다 — 클라이언트에서 더 잘게 가를 방법이 없다).
import http from 'k6/http';
import exec from 'k6/execution';
import sse from 'k6/x/sse';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import {
  BASE_URL,
  LOAD_USER_EMAIL,
  LOAD_USER_PASSWORD,
  SSE_PERF_ID,
  SSE_SUBSCRIBER_STEPS,
  SSE_STEP_DURATION,
  SSE_STEP_RAMP,
  SSE_MUTATE_RATE,
  SSE_PROBE_PER_MINUTE,
  SSE_PROBE_TIMEOUT_MS,
} from '../config/env.js';
import { baseOptions } from '../config/options.js';
import { login } from '../lib/auth.js';
import { jsonField } from '../lib/json.js';

// 409(좌석 선점 불가)는 정상 경합이다. 200 을 반드시 함께 넣는다 — 이 콜백은 run 전역이라
// lib/auth.js 의 로그인(200)까지 덮는다(openrun-e2e.js 와 같은 함정).
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

// k6 duration 문자열을 초로. 회차 전체 길이를 계단 정의에서 유도하기 위해 필요하다.
// 손으로 맞추게 두면 mutator 가 마지막 계단보다 먼저 끝나 '구독자 600명 구간의 이벤트 0건' 이
// 되는데, 그 회차는 실패가 아니라 '지연 0ms' 처럼 보여서 조용히 거짓 결론이 된다.
function toSeconds(d) {
  const m = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(String(d).trim());
  if (!m) {
    throw new Error(`기간 형식을 해석할 수 없다: '${d}' (예: 30s, 10m)`);
  }
  const n = Number(m[1]);
  return m[2] === 'ms' ? n / 1000 : m[2] === 's' ? n : m[2] === 'm' ? n * 60 : n * 3600;
}

const STEP_RAMP_SEC = toSeconds(SSE_STEP_RAMP);
const STEP_HOLD_SEC = toSeconds(SSE_STEP_DURATION);
const TOTAL_SEC = SSE_SUBSCRIBER_STEPS.length * (STEP_RAMP_SEC + STEP_HOLD_SEC);
const TOTAL_DURATION = `${TOTAL_SEC}s`;

// 계단: 각 단계마다 (짧은 램프 + 유지). 목표치는 단조 증가라 램프다운이 없다.
const subscriberStages = SSE_SUBSCRIBER_STEPS.flatMap((target) => [
  { duration: SSE_STEP_RAMP, target },
  { duration: SSE_STEP_DURATION, target },
]);

export const options = {
  scenarios: {
    // VU 1개 = SSE 커넥션 1개. sse.open 이 커넥션이 닫힐 때까지 블로킹하므로 VU 수가 곧 동시
    // 구독자 수다. gracefulStop/gracefulRampDown 을 0 으로 두는 것은 블로킹된 VU 를 기다리지
    // 않기 위해서다(기다리면 회차가 30분 서버 타임아웃까지 안 끝난다).
    subscribers: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: subscriberStages,
      gracefulRampDown: '0s',
      gracefulStop: '0s',
      exec: 'subscribe',
    },
    // 이벤트 발생원. 이 회차의 통제 변수라 전 구간 고정 도착률이다.
    mutators: {
      executor: 'constant-arrival-rate',
      rate: SSE_MUTATE_RATE,
      timeUnit: '1s',
      duration: TOTAL_DURATION,
      preAllocatedVUs: Math.max(10, SSE_MUTATE_RATE * 3),
      maxVUs: Math.max(50, SSE_MUTATE_RATE * 20),
      exec: 'mutate',
    },
    // 전파 지연 SSOT. 낮은 도착률로 회차 전 구간에 고르게 뿌려, 계단별 값을 시각으로 잘라 읽는다.
    probe: {
      executor: 'constant-arrival-rate',
      rate: SSE_PROBE_PER_MINUTE,
      timeUnit: '1m',
      duration: TOTAL_DURATION,
      preAllocatedVUs: 4,
      maxVUs: 20,
      gracefulStop: '0s',
      exec: 'probe',
    },
  },
  // 값은 재정의하지 않는다. 다만 http_req_duration 임계는 이 회차에서 의미가 약하다 —
  // SSE 커넥션은 수십 분간 열려 있어 '요청 지연' 이라는 개념이 성립하지 않는다. 판정은
  // sse_propagation_ms 와 수신 누락률로 한다.
  thresholds: baseOptions.thresholds,
};

const sseEventsReceived = new Counter('sse_events_received'); // seat-status-changed 수신 총합
const sseConnected = new Counter('sse_connected'); // connected 이벤트 = 구독 성립 건수
const sseConnectDuration = new Trend('sse_connect_duration', true);
const sseConnectionClosed = new Counter('sse_connection_closed'); // 재연결·조기 종료
const sseConnectionError = new Counter('sse_connection_error');

const propagation = new Trend('sse_propagation_ms', true);
const probeBookingDuration = new Trend('sse_probe_booking_duration', true);
const probeMatched = new Counter('sse_probe_matched');
const probeTimeout = new Counter('sse_probe_timeout');
const probeBooked = new Rate('sse_probe_booked');

const mutateCreated = new Rate('sse_mutate_created'); // 201 = 예매 생성 = 이벤트 1건 발생 예정
const mutateConflict = new Rate('sse_mutate_conflict'); // 409. 유일 배정이면 0 이어야 한다
const mutateExhausted = new Rate('sse_mutate_exhausted'); // >0 이면 이 회차는 무효(좌석 부족)

const STREAM_URL = `${BASE_URL}/api/v1/seat/${SSE_PERF_ID}/seat-status/stream`;

export function setup() {
  const token = login(LOAD_USER_EMAIL, LOAD_USER_PASSWORD);

  // 예매 대상 좌석 코호트. openrun-e2e.js 와 같은 방식으로 (min, step, count) 만 넘긴다 —
  // 배열 전체를 setup 데이터로 넘기면 k6 가 VU 마다 복사해 생성기 메모리를 먹는다.
  const res = http.get(`${BASE_URL}/api/v1/seat/${SSE_PERF_ID}/seat-layouts`, {
    headers: { 'Accept-Encoding': 'gzip' },
    tags: { name: 'setup_seat_layouts' },
  });
  const seats = jsonField(res, 'result');
  if (!Array.isArray(seats)) {
    fail(`setup: 좌석맵 조회 실패 perfId=${SSE_PERF_ID} status=${res.status}`);
  }

  const ids = seats.filter((s) => s.seat_status === 'AVAILABLE').map((s) => s.seat_id);
  if (ids.length === 0) {
    fail(`setup: perfId=${SSE_PERF_ID} 에 AVAILABLE 좌석이 없다. seed_seat_counts.sql 을 먼저 돌린다`);
  }
  ids.sort((a, b) => a - b);

  // 간격이 일정해야 (min + step*offset) 산술 배정이 성립한다. 깨져 있으면 다른 코호트가 중간
  // 좌석을 점유한 것이고, 그대로 돌리면 409 가 '정상 경합' 으로 위장되어 이벤트 수가 조용히 준다.
  const min = ids[0];
  const step = ids.length > 1 ? ids[1] - ids[0] : 1;
  for (let i = 1; i < ids.length; i++) {
    if (ids[i] - ids[i - 1] !== step) {
      fail(
        `setup: perfId=${SSE_PERF_ID} 의 AVAILABLE seat_id 간격이 일정하지 않다 ` +
          `(step=${step} 인데 ids[${i}]=${ids[i]}). 코호트를 재시딩한 뒤 재시도한다`,
      );
    }
  }

  // 좌석은 iteration 당 1개씩 비가역 소모된다. 부족하면 회차 후반이 통째로 이벤트 0 이 되므로
  // 시작 전에 끊는다. probe 는 뒤에서부터 배정하므로 두 축이 만나면 안 된다.
  const needMutate = Math.ceil(SSE_MUTATE_RATE * TOTAL_SEC);
  const needProbe = Math.ceil((SSE_PROBE_PER_MINUTE * TOTAL_SEC) / 60);
  if (ids.length < needMutate + needProbe) {
    fail(
      `setup: AVAILABLE 좌석 ${ids.length}석으로는 부족하다 ` +
        `(mutate ${needMutate} + probe ${needProbe} = ${needMutate + needProbe} 필요). ` +
        `seed_seat_counts.sql 의 @seats 를 올리거나 SSE_MUTATE_RATE 를 낮춘다`,
    );
  }

  console.log(
    `[setup] perfId=${SSE_PERF_ID} 구독자 계단=${SSE_SUBSCRIBER_STEPS.join(',')} ` +
      `x ${SSE_STEP_DURATION} (총 ${TOTAL_DURATION}), 이벤트율=${SSE_MUTATE_RATE}/s, ` +
      `AVAILABLE ${ids.length}석 (mutate ${needMutate} + probe ${needProbe} 사용 예정)`,
  );
  return { token, seatIdMin: min, seatIdStep: step, seatCount: ids.length };
}

// ── 구독자 ───────────────────────────────────────────────────────────────────
// 이벤트를 파싱하지 않고 세기만 한다. 구독자 600명 x 5 events/s = 3,000 parse/s 는 생성기 쪽
// 비용이라 측정 대상(서버)이 아닌 곳에서 지연을 만든다. 파싱이 필요한 것은 probe 뿐이다.
export function subscribe() {
  const startedAt = Date.now();
  const ok = sse.open(STREAM_URL, { tags: { name: 'sse_subscribe' } }, function (client) {
    client.on('open', function () {
      sseConnectDuration.add(Date.now() - startedAt);
    });
    client.on('event', function (e) {
      if (e.name === 'connected') {
        sseConnected.add(1);
        return;
      }
      if (e.name === 'seat-status-changed') {
        sseEventsReceived.add(1);
      }
    });
    client.on('error', function () {
      sseConnectionError.add(1);
    });
  });
  // 여기 도달했다 = 커넥션이 닫혔다. ramping-vus 가 곧바로 다음 iteration 을 시작해 재연결하므로
  // 동시 구독자 수는 유지된다. 이 카운터가 크면 서버가 커넥션을 못 붙들고 있다는 뜻이다.
  sseConnectionClosed.add(1);
  check(ok, { 'sse open ok': (r) => r !== null && r !== false });
}

// ── 이벤트 발생원 ────────────────────────────────────────────────────────────
// 좌석 HOLD 진입점은 HTTP 가 아니라 seat-service 의 BookingCreatedEventListener(Kafka) 하나다
// (런북 §10.1). k6 가 칠 수 있는 것은 예매 생성까지이고 HOLD 는 그 뒤에 비동기로 일어난다.
export function mutate(data) {
  // exec.scenario.iterationInTest = 이 시나리오의 전역 0-base 단조 카운터.
  // __VU*1000+__ITER 은 arrival-rate 에서 쓰면 안 된다(VU 재사용으로 인덱스가 충돌한다).
  const idx = exec.scenario.iterationInTest;
  if (idx >= data.seatCount) {
    mutateExhausted.add(true);
    return;
  }
  mutateExhausted.add(false);

  const seatId = data.seatIdMin + data.seatIdStep * idx;
  const res = bookSeat(data.token, seatId, 'sse_mutate_booking');
  mutateCreated.add(res.status === 201);
  mutateConflict.add(res.status === 409);
}

// ── probe ────────────────────────────────────────────────────────────────────
// 좌석은 코호트 뒤에서부터 배정한다. mutate 는 앞에서부터 쓰므로 둘이 만나지 않는다
// (setup 이 총량을 미리 검증했다).
export function probe(data) {
  const idx = exec.scenario.iterationInTest;
  const seatId = data.seatIdMin + data.seatIdStep * (data.seatCount - 1 - idx);

  let sentAt = 0;
  let matched = false;

  sse.open(STREAM_URL, { tags: { name: 'sse_probe' } }, function (client) {
    client.on('event', function (e) {
      const now = Date.now();

      if (e.name === 'connected') {
        // 구독이 성립한 뒤에 예매를 건다. 순서가 반대면 이벤트가 구독 이전에 발행돼
        // 영원히 기다리게 되고, 그 회차의 지연 표본이 통째로 사라진다.
        sentAt = Date.now();
        const res = bookSeat(data.token, seatId, 'sse_probe_booking');
        probeBookingDuration.add(res.timings.duration);
        probeBooked.add(res.status === 201);
        if (res.status !== 201) {
          client.close();
        }
        return;
      }

      if (e.name === 'seat-status-changed' && !matched) {
        const payload = parseEvent(e.data);
        // 앱 ObjectMapper 가 전역 snake_case 라 seat_id 로 온다. camelCase 도 함께 보는 것은
        // 직렬화 설정이 바뀌었을 때 회차 전체(EC2 과금 + 30분)를 날리지 않기 위한 보험이다.
        const sid = payload === null ? null : payload.seat_id !== undefined ? payload.seat_id : payload.seatId;
        if (sid === seatId) {
          matched = true;
          propagation.add(now - sentAt);
          probeMatched.add(1);
          client.close();
          return;
        }
      }

      // 자기 이벤트가 안 오는 경우의 탈출구. mutator 가 초당 여러 건을 만들고 있어 이 핸들러는
      // 계속 호출되므로 타이머 없이도 동작한다. 이벤트가 아예 멈춘 상황은 그 자체가 관측 대상이고
      // scenario 의 gracefulStop:'0s' 가 회차 종료 시 VU 를 끊는다.
      if (sentAt > 0 && now - sentAt > SSE_PROBE_TIMEOUT_MS) {
        probeTimeout.add(1);
        client.close();
      }
    });
    client.on('error', function () {
      sseConnectionError.add(1);
    });
  });
}

function bookSeat(token, seatId, tagName) {
  return http.post(
    `${BASE_URL}/api/v1/booking`,
    JSON.stringify({ performance_id: SSE_PERF_ID, seat_id: seatId }),
    {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      tags: { name: tagName },
    },
  );
}

// SSE 의 data 는 'connected' 같은 평문도 오고, 포화 구간에서는 잘린 프레임이 올 수도 있다.
// 파싱 실패로 iteration 이 죽으면 하필 관측해야 할 구간에서 지표가 비뚤어진다(lib/json.js 와 같은 선).
function parseEvent(raw) {
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw);
  } catch (err) {
    return null;
  }
}
