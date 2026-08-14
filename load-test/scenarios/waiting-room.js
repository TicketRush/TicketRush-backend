// (i) 대기열 — 상태 확인 경로 용량(R) 실측 + 1만 VU 유입 제어 검증 (#472 / ADR 0009).
//
// ── 이 시나리오가 답해야 하는 것 ─────────────────────────────────────────────
// ADR 0009 §3 은 폴링 주기 하한을 `T ≥ N / R` 로 정하고, R(상태 확인 경로가 감당하는 RPS)을
// **아직 실측하지 못했다**고 명시한다. #470 은 "#348 에서 실측한 폴링 경로 무릎" 을 입력으로 쓰라고
// 적었지만 #348 은 회선 제약에 막혀 앱의 무릎에 도달조차 못 했다 — 그 수치는 존재하지 않는다.
// 지금 쓰는 R=400 은 #529 seat-counts 포화점 396.75 RPS 를 빌린 보수적 하한이다.
//   status 회차 = R 을 실측해 T 를 확정한다(그 결과로 ADR §3 을 갱신한다).
//   flood  회차 = 예매 경로 RPS 가 유입 규모와 무관하게 입장 허용량에서 평평한지 본다.
//
// ── #555 — flood 가 계단 회차가 됐다 ────────────────────────────────────────
// admit-rate-per-second(20)의 무릎을 계단으로 실측한다. 두 가지가 바뀌었다.
//   ① 부하 모델 — ramping-vus + VU 당 1회 가드는 실효 도착률이 상수인 단일 동작점이라
//      계단을 만들 수 없었다(#554 §8). ramping-arrival-rate 로 옮겼다.
//   ② 승급 후 여정 — 예매 1콜뿐이라 admit 을 올려도 예매 API 만 때리는 회차였다. 그 상태의
//      상한은 거짓 결론이므로 좌석맵 조회와 SSE 구독 체류를 넣었다.
// 좌석 선점은 새로 넣을 호출이 없다 — HTTP 진입점이 없어 예매 생성이 그 역할이다(bookSeat 주석).
//
// ── 서버 지시 폴링 — 저장소에 선례가 없는 패턴 ───────────────────────────────
// 다른 시나리오는 sleep(고정값)으로 자지만 여기서는 **서버가 응답에 담아 준 주기**로 잔다. 그래서
// 응답이 깨졌을 때의 폴백이 안전장치가 아니라 필수다 — 아래 nextPollSeconds() 주석 참조.
//
// ── 인증을 왜 로그인으로 하지 않는가 ─────────────────────────────────────────
// 대기열은 "서로 다른 1만 명" 이 전제인데, 1만 번 로그인은 bcrypt(cost 10) 비용이 2 vCPU 를
// 통째로 먹어 측정 대상이 아니라 auth-service 를 재게 된다. 게이트웨이와 같은 시크릿으로 k6 가
// 직접 서명한다(시크릿은 커밋하지 않고 -e 로 주입 — 비밀번호와 같은 규율).
// ⚠ user_id 는 실제로 시딩된 범위여야 한다. 대기열 자체는 DB 를 타지 않지만 flood 의 마지막
//   단계인 예매는 탄다. 코호트 규모는 회차 A 결과에 달려 있어 런북 §16 에서 확정한다.
import http from 'k6/http';
// ⚠ 이 import 때문에 이 시나리오는 k6-sse 이미지에서만 돈다(load-test/Dockerfile.k6-sse).
//   기본 grafana/k6 로 실행하면 'k6/x/sse' 해석 실패로 즉시 죽는다 — 부하가 시작조차 안 되므로
//   조용한 오염은 아니다. status 프로파일도 같은 파일이라 함께 영향을 받는다(런북 §16.4).
import sse from 'k6/x/sse';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import exec from 'k6/execution';
import { sleep, fail } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import {
  BASE_URL,
  QUEUE_PROFILE,
  QUEUE_PERF_ID,
  QUEUE_STATUS_STAGE_RATES,
  QUEUE_STATUS_STAGE_DURATION,
  QUEUE_STATUS_PRE_ALLOCATED_VUS,
  QUEUE_STATUS_MAX_VUS,
  QUEUE_PRELOAD_SIZE,
  QUEUE_ARRIVAL_RATE,
  QUEUE_ARRIVAL_RAMP_SECONDS,
  QUEUE_ARRIVAL_HOLD_SECONDS,
  QUEUE_COHORT_SIZE,
  QUEUE_FLOOD_PRE_ALLOCATED_VUS,
  QUEUE_FLOOD_MAX_VUS,
  QUEUE_GRACEFUL_STOP,
  QUEUE_SEAT_DWELL_SECONDS,
  QUEUE_SSE_ENABLED,
  QUEUE_FALLBACK_POLL,
  QUEUE_JITTER,
  QUEUE_MAX_POLLS,
} from '../config/env.js';
import { baseOptions } from '../config/options.js';
import { jsonField } from '../lib/json.js';

const JWT_SECRET = __ENV.QUEUE_JWT_SECRET || '';
const USER_ID_MIN = Number(__ENV.QUEUE_USER_ID_MIN || 1);
// 좌석 id 오프셋. flood 의 예매는 (seat_id, performance_id) 쌍으로 검증되는데(BookingValidateReferences
// UseCase), prod 는 seat_id 가 이미 13만대까지 소진돼 있어 user id 와 같은 번호대를 쓸 수 없다.
// seed_queue_flood.sql 이 만든 실제 좌석 id 범위를 -e 로 주입한다(기본값은 user id 와 1:1 = 종전 동작).
const SEAT_ID_MIN = Number(__ENV.QUEUE_SEAT_ID_MIN || USER_ID_MIN);
// 대기열 개시(ADMIN) 전용. 실제 계정일 필요는 없다 — 개시는 Redis 만 건드리고 DB 를 타지 않는다.
const ADMIN_USER_ID = Number(__ENV.QUEUE_ADMIN_USER_ID || 1);

// 계단 사이 전환 램프. ramping-arrival-rate 의 stage 는 target 까지 선형 변화하므로 "계단" 을
// 만들려면 (짧은 램프 + 유지) 두 개가 필요하다. seat-counts.js 와 같은 형태다.
const STEP_RAMP = '10s';

function statusStages() {
  const rates = QUEUE_STATUS_STAGE_RATES; // 오타·빈 값은 config/env.js 가 로드 시점에 끊는다
  const stages = [{ target: rates[0], duration: QUEUE_STATUS_STAGE_DURATION }];
  for (let i = 1; i < rates.length; i++) {
    stages.push({ target: rates[i], duration: STEP_RAMP });
    stages.push({ target: rates[i], duration: QUEUE_STATUS_STAGE_DURATION });
  }
  return stages;
}

const SCENARIOS = {
  status: {
    poll: {
      executor: 'ramping-arrival-rate',
      timeUnit: '1s',
      startRate: QUEUE_STATUS_STAGE_RATES[0],
      preAllocatedVUs: QUEUE_STATUS_PRE_ALLOCATED_VUS,
      maxVUs: QUEUE_STATUS_MAX_VUS,
      stages: statusStages(),
      exec: 'pollOnly',
    },
  },
  flood: {
    // iteration 1개 = 대기자 1명. 진입 후에는 서버가 지시한 주기로만 깨어나므로 커넥션을 물고
    // 있지 않다 — 폴링을 택한 이유가 정확히 이것이다(ADR 0009 기각안 1).
    //
    // stages 는 계단이 아니라 코호트 주입기다. QUEUE_ADMIT_RATE 는 서버값이고 반영에 게이트웨이
    // --force-recreate 가 필요해서(런북 §16.4 G2) **계단 하나 = 회차 하나**이고, 이 형상은
    // 계단 사이의 통제 변수다. 도착률은 그 회차의 admit 보다 커야 대기가 실제로 쌓인다.
    flood: {
      executor: 'ramping-arrival-rate',
      timeUnit: '1s',
      startRate: 0,
      preAllocatedVUs: QUEUE_FLOOD_PRE_ALLOCATED_VUS,
      maxVUs: QUEUE_FLOOD_MAX_VUS,
      stages: [
        { target: QUEUE_ARRIVAL_RATE, duration: `${QUEUE_ARRIVAL_RAMP_SECONDS}s` },
        { target: QUEUE_ARRIVAL_RATE, duration: `${QUEUE_ARRIVAL_HOLD_SECONDS}s` },
      ],
      // 유입이 끝나도 대기 중인 사람이 남아 있다. 여기서 끊으면 소화 시간(주 지표)이 통째로
      // 사라진다 — 마지막 순번은 개시로부터 코호트/admit 초에 승급한다.
      gracefulStop: QUEUE_GRACEFUL_STOP,
      exec: 'journey',
    },
  },
};

export const options = {
  scenarios: SCENARIOS[QUEUE_PROFILE],
  // 값은 재정의하지 않는다(seat-counts.js 와 같은 선) — 포화 구간에서 p(95)<800 이 깨지는 것 자체가
  // 관측 대상이다. dropped_iterations 도 threshold 에 넣지 않는다(포화 신호이지 실패가 아니다).
  thresholds: baseOptions.thresholds,
  // setup 이 대기열을 미리 채운다. 1만 건이면 수십 초가 걸리므로 기본값 60s 로는 부족하다.
  setupTimeout: '15m',
};

const statusDuration = new Trend('queue_status_duration', true);
const waitToAdmit = new Trend('queue_wait_to_admit_seconds');
const pollsPerUser = new Trend('queue_polls_per_user');
const admitted = new Rate('queue_admitted');
const bookingOk = new Rate('queue_booking_ok');
const bookingForbidden = new Rate('queue_booking_forbidden');
// > 0 이면 fail-closed(ADR 0008)가 발동한 것이다. 이 회차는 대기열 성능이 아니라 Redis 장애를
// 측정한 것이므로 무효다.
const statusUnavailable = new Rate('queue_status_unavailable');
// 안전 상한에 걸려 끝난 VU. > 0 이면 입장 허용량이 유입을 소화하지 못한 것이다.
const pollsExhausted = new Counter('queue_polls_exhausted');
// 진입(enqueue)에서 대기 토큰을 못 얻어 여정이 시작조차 못 한 VU. 실효 코호트 = 유입 - 이 값이다.
// #549 는 이 계측이 없어 724명(7.24%)을 http_req_failed 로 역산해서야 알았고, B-1 때는 '대기 3,420 +
// 임계치 3,000' 으로 짐작했다 — 회차 규모를 역산에 맡기면 무엇을 잰 회차인지가 흐려진다.
const enqueueFailed = new Counter('queue_enqueue_failed');
// status 회차 전용 무효 판정. 폴링 대상이 회차 도중 승급하면 그 이후 폴링은 입장 토큰 SET 이 붙어
// Redis 명령이 2회(GET+ZRANK) 에서 3회로 늘고 noeviction Redis 에 쓰기까지 생긴다. 재려던 것은
// '대기 중인 사용자의 폴링' 인데 다른 경로를 재게 되므로 R 이 과소평가된다.
//   threshold = 경과 x admit-rate 라 PRELOAD 1만 - admit-rate 20 이면 8분 20초에 승급한다.
//   회차가 20분(4계단 x 5분)이므로 반드시 걸린다 → status 회차는 QUEUE_ADMIT_RATE 를 낮춘다(런북 §16.4).
const statusAdmittedLeak = new Rate('queue_status_admitted_leak');
// 코호트를 넘어선 iteration. 좌석·계정이 시딩된 만큼만 있으므로 그 뒤는 전부 404 가 되고
// 예매 경로 RPS 가 과소 집계된다. > 0 이면 회차 무효다(도착률 x 유입시간 > 코호트).
const cohortExhausted = new Counter('queue_cohort_exhausted');
// 이 회차의 주 지표 — 대기열 개시부터 이 사람이 예매를 마치기까지. max 가 '전원 소화 시간' 이다.
// 승급 임계치가 개시 시각의 함수라(WaitingRoomPolicy.admittedThreshold) 개시 시각이 기준점으로
// 정확하고, 같은 k6 프로세스의 같은 시계라 EC2 와 생성기의 시계 차를 타지 않는다.
const drainSeconds = new Trend('queue_drain_seconds');
// 승급 이후 구간(좌석맵 + SSE 체류 + 예매). dropped_iterations 를 가르는 보조축이다 —
// 이것이 평평한데 dropped 가 나면 생성기 VU 부족이고, 이것이 부풀면 대상 포화다.
const postAdmitSeconds = new Trend('queue_post_admit_seconds');
// 승급 후 여정 — 좌석맵. #549·#554 는 승급 후가 예매 1콜뿐이라 admit 을 올려도 예매 API 만
// 때리는 회차였다. 그 상태로 나온 상한은 낙관적인 거짓 결론이다(#555 본문).
const seatmapDuration = new Trend('queue_seatmap_duration', true);
const seatmapOk = new Rate('queue_seatmap_ok');
// SSE 구독. 연결 지연과 실패를 따로 센다 — 구독이 안 되면 이 회차의 SSE 축이 통째로 비는데
// 예매는 그대로 성공하므로 요약만 보면 정상으로 보인다.
const sseConnectDuration = new Trend('queue_sse_connect_duration', true);
const sseSubscribeFailed = new Counter('queue_sse_subscribe_failed');
const sseConnectionError = new Counter('queue_sse_connection_error');

function signAccessToken(userId, role) {
  const header = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
  const now = Math.floor(Date.now() / 1000);
  const payload = encoding.b64encode(
    JSON.stringify({
      sub: String(userId),
      role: role || 'USER',
      type: 'access',
      iat: now,
      exp: now + 7200,
    }),
    'rawurl',
  );
  const signature = crypto.hmac('sha256', JWT_SECRET, `${header}.${payload}`, 'base64rawurl');
  return `${header}.${payload}.${signature}`;
}

/**
 * 대기열을 연다(ADMIN). 승급 임계치의 기준점을 심는 호출이라 회차 시작 전에 정확히 한 번 해야 한다.
 *
 * 개시 시각이 이전 회차 것으로 남아 있으면 threshold = 경과 x rate 가 이미 수십만이라 전원이 첫
 * 폴링에서 즉시 승급한다 — 예매 RPS 가 입장 허용량에서 평평한지 보려던 회차가 그냥 스파이크가 된다.
 * 그래서 런북 §16.4 의 리셋(queue:* 삭제)이 이 호출보다 먼저다.
 */
function openQueue() {
  const res = http.post(`${BASE_URL}/api/v1/queue/${QUEUE_PERF_ID}/open`, null, {
    headers: { Authorization: `Bearer ${signAccessToken(ADMIN_USER_ID, 'ADMIN')}` },
    tags: { name: 'queue_open' },
  });
  if (res.status !== 200) {
    fail(`대기열 개시 실패(status=${res.status}). QUEUE_ADMIN_USER_ID 가 ADMIN 계정인지 확인할 것.`);
  }
}

/** 대기열 진입 → 응답. 실패는 jsonField 가 null 로 흡수한다(그 VU 의 여정은 성립하지 않는다). */
function enqueue(userId) {
  return http.post(`${BASE_URL}/api/v1/queue/${QUEUE_PERF_ID}/enqueue`, null, {
    headers: { Authorization: `Bearer ${signAccessToken(userId)}` },
    tags: { name: 'queue_enqueue' },
  });
}

/**
 * 서버가 지시한 다음 폴링까지의 초 + 지터.
 *
 * 응답이 깨졌을 때(5xx·타임아웃·본문 파싱 실패) 0 이나 undefined 로 떨어지면 k6 가 sleep 없이
 * 재폴링해 스스로 DDoS 가 된다 — 하필 대상이 죽어가는 구간에서 정확히 그렇게 되므로, 폴백은
 * 반드시 ADR 0009 §3 의 보수적 T(25초) 이상이어야 한다.
 */
function nextPollSeconds(res) {
  const next = jsonField(res, 'result.next_poll_after_seconds');
  const base = Number.isFinite(next) && next > 0 ? next : QUEUE_FALLBACK_POLL;
  return base * (1 - QUEUE_JITTER + Math.random() * 2 * QUEUE_JITTER);
}

const SEATMAP_URL = `${BASE_URL}/api/v1/seat/${QUEUE_PERF_ID}/seat-layouts`;
const STREAM_URL = `${BASE_URL}/api/v1/seat/${QUEUE_PERF_ID}/seat-status/stream`;
const DWELL_MS = QUEUE_SEAT_DWELL_SECONDS * 1000;

/**
 * 좌석 선점. <b>HTTP 진입점이 없어 예매 생성이 그 역할을 한다.</b>
 *
 * SeatController 는 GET 뿐이고 HOLD 진입점은 seat-service 의 BookingCreatedEventListener(Kafka)
 * 하나다(런북 §10.1 · openrun-e2e.js · seat-sse-fanout.js 가 같은 사실을 기록). k6 가 칠 수 있는
 * 최대가 여기까지이고 HOLD 는 그 뒤에 비동기로 일어난다 — #555 완료조건의 "좌석 선점 추가" 는
 * 새로 넣을 호출이 없다는 뜻이지 여정에 빠져 있다는 뜻이 아니다.
 */
function bookSeat(userId, seatId, entryToken) {
  return http.post(
    `${BASE_URL}/api/v1/booking`,
    JSON.stringify({ performance_id: QUEUE_PERF_ID, seat_id: seatId }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${signAccessToken(userId)}`,
        'X-Entry-Token': entryToken,
      },
      tags: { name: 'queue_booking' },
    },
  );
}

/**
 * 좌석 선택 화면 체류 — SSE 를 구독한 채 머물다가 닫는다. 예매는 <b>구독 안에서</b> 건다.
 *
 * sse.open 은 커넥션이 닫힐 때까지 블로킹이라 바깥에서 sleep 으로 체류를 만들 수 없다. 그래서
 * seat-sse-fanout.js 의 probe 와 같은 구조를 쓴다 — 구독이 성립한 뒤(connected) 예매를 걸고,
 * 체류 시간이 지나면 닫는다.
 *
 * <b>예매를 구독보다 먼저 걸면 안 된다.</b> 이 핸들러는 이벤트가 와야 깨어나는데, 회차 첫 승급자는
 * 자기 예매가 만드는 이벤트 말고는 깨울 것이 없다. 순서가 반대면 그 이벤트가 구독 이전에 발행돼
 * 영원히 깨어나지 못한다.
 *
 * ⚠ 그래도 회차 맨 처음 한두 명은 자기 이벤트 1개만 받고 체류 시간을 못 채워 갇힐 수 있다
 *   (이후 승급자들의 이벤트가 흐르기 시작하면 풀린다). 그 VU 는 gracefulStop 이 끊고, 예매와
 *   소화 시간은 이미 기록된 뒤다.
 */
function dwellAndBook(userId, seatId, entryToken) {
  const startedAt = Date.now();
  let connectedAt = 0;
  let res = null;

  sse.open(STREAM_URL, { tags: { name: 'queue_sse' } }, function (client) {
    client.on('event', function (e) {
      const now = Date.now();
      if (e.name === 'connected') {
        sseConnectDuration.add(now - startedAt);
        connectedAt = now;
        res = bookSeat(userId, seatId, entryToken);
        return;
      }
      // 남의 좌석 이벤트도 체류 시간을 재는 시계 역할을 한다. 파싱하지 않는다 — 이벤트를
      // 파싱하는 비용은 생성기 쪽이라 측정 대상이 아닌 곳에서 지연을 만든다(#403 선례).
      if (connectedAt > 0 && now - connectedAt >= DWELL_MS) {
        client.close();
      }
    });
    client.on('error', function () {
      sseConnectionError.add(1);
    });
  });

  if (res === null) {
    // 구독이 안 됐거나 connected 이벤트를 못 받았다. 예매까지 건너뛰면 그 사람이 통째로 사라져
    // 예매 경로 RPS 가 과소 집계되므로, 구독 없이 예매만 진행하고 그 사실을 센다.
    sseSubscribeFailed.add(1);
    res = bookSeat(userId, seatId, entryToken);
  }
  return res;
}

function pollStatus(waitingToken) {
  const res = http.get(`${BASE_URL}/api/v1/queue/${QUEUE_PERF_ID}/status`, {
    headers: { 'X-Waiting-Token': waitingToken, 'Accept-Encoding': 'gzip' },
    tags: { name: 'queue_status' },
  });
  statusDuration.add(res.timings.duration);
  statusUnavailable.add(res.status === 503);
  return res;
}

export function setup() {
  if (!JWT_SECRET) {
    fail('QUEUE_JWT_SECRET 을 -e 로 주입해야 한다(게이트웨이 jwt.secret 과 같은 값, 커밋 금지).');
  }

  openQueue();
  // 승급 임계치 = (지금 - 개시) x admit-rate 다(WaitingRoomPolicy.admittedThreshold).
  // 소화 시간의 기준점이 이 시각이라 openQueue() 직후에 잡는다.
  const openedAtMs = Date.now();

  if (QUEUE_PROFILE !== 'status') {
    // 계획 iteration = 램프(평균 절반) + 유지. 코호트를 넘으면 그 뒤는 전부 404 라 회차가
    // 무효가 된다 — 20분과 EC2 과금을 쓰기 전에 여기서 끊는다.
    const planned = Math.ceil(
      QUEUE_ARRIVAL_RATE * (QUEUE_ARRIVAL_RAMP_SECONDS / 2 + QUEUE_ARRIVAL_HOLD_SECONDS),
    );
    if (planned > QUEUE_COHORT_SIZE) {
      fail(
        `계획 iteration ${planned} 이 코호트 ${QUEUE_COHORT_SIZE} 를 넘는다. ` +
          `QUEUE_ARRIVAL_RATE/HOLD 를 줄이거나 seed_queue_flood.sql 의 @cohort_size 를 키울 것.`,
      );
    }
    console.log(
      `[setup] profile=flood perfId=${QUEUE_PERF_ID} 도착률=${QUEUE_ARRIVAL_RATE}/s ` +
        `계획 iteration=${planned} / 코호트=${QUEUE_COHORT_SIZE} (대기열 개시 완료)`,
    );
    return { openedAtMs };
  }

  // ZRANK 는 skiplist 탐색이라 ZSET 크기에 로그 비례한다. 빈 대기열을 재면 실회차보다 낙관적인
  // 값이 나와 R 을 과대평가하고, 그 값이 그대로 폴링 주기 하한이 되어 운영에서 터진다.
  console.log(`[setup] 대기열 사전 적재 ${QUEUE_PRELOAD_SIZE}명 (perfId=${QUEUE_PERF_ID})`);
  let lastToken = null;
  for (let i = 0; i < QUEUE_PRELOAD_SIZE; i++) {
    const token = jsonField(enqueue(USER_ID_MIN + i), 'result.waiting_token');
    if (token) {
      lastToken = token;
    }
  }
  if (!lastToken) {
    fail('사전 적재가 대기 토큰을 하나도 얻지 못했다 — QUEUE_ENABLED / Redis 상태를 확인할 것.');
  }

  // 폴링에 쓰는 토큰은 마지막에 진입한 사람 것이다 = 순번이 가장 뒤 = ZRANK 탐색 비용이 가장 큰
  // 지점. 회차가 보수적인 방향으로 틀리게 만든다.
  console.log(`[setup] 적재 완료. 폴링 대상 순번 ≈ ${QUEUE_PRELOAD_SIZE}`);
  return { waitingToken: lastToken };
}

/** status 회차 — 상태 확인 경로만 두드려 R 을 잰다. 사용자 행동이 아니라 경로 용량 측정이라 sleep 이 없다. */
export function pollOnly(data) {
  const res = pollStatus(data.waitingToken);
  // > 0 이면 폴링 대상이 승급해 측정 경로가 바뀌었다 = 이 회차는 R 을 재지 못했다.
  statusAdmittedLeak.add(Boolean(jsonField(res, 'result.entry_token')));
}

/**
 * flood 회차 — 진입 → 서버 지시 폴링 → 입장 → 예매의 전 여정. <b>iteration 하나가 한 명이다.</b>
 *
 * 이전에는 ramping-vus + VU 별 `journeyDone` 가드로 "VU 1개 = 대기자 1명" 을 지켰다. 그 조합이
 * 실효 도착률을 `VU수 / 램프시간` 상수로 못 박아 <b>단일 동작점</b>을 만들었고, #554 가 그 사실을
 * 자기 한계로 확정했다(report §8 — "다이얼이 없어 원리상 스윕이 아니다").
 *
 * arrival-rate 에서는 도착률이 곧 다이얼이고, 한 사람 = 한 iteration 이라 가드가 필요 없다.
 * 대신 <b>사람의 신원을 VU 가 아니라 iteration 번호로 매겨야 한다</b> — VU 는 재사용되므로
 * `exec.vu.idInTest` 로 매기면 같은 계정·좌석이 여러 번 나온다. 재진입은 순번이 보존되므로
 * (WaitingRoomService.registerIfAbsent) 두 번째부터는 즉시 승급해 예매만 반복하게 되고,
 * 그러면 '예매 RPS 가 허용량에서 평평한가' 를 보려던 회차에서 평평하지 않은 이유가 대기열이
 * 아니라 생성기가 된다.
 */
export function journey(data) {
  // exec.scenario.iterationInTest = 이 시나리오의 전역 0-base 단조 카운터.
  // __VU*1000+__ITER 은 arrival-rate 에서 쓰면 안 된다(VU 재사용으로 인덱스가 충돌한다).
  const idx = exec.scenario.iterationInTest;
  if (idx >= QUEUE_COHORT_SIZE) {
    // 코호트 밖이면 계정·좌석이 없어 예매가 404 다. 그 iteration 을 그대로 돌리면 예매 경로
    // RPS 가 과소 집계되므로 세고 빠진다 — setup() 이 계획 iteration 으로 미리 끊지만,
    // 도착률이 실제로 초과 달성되는 경우까지 여기서 잡는다.
    cohortExhausted.add(1);
    return;
  }

  // 시드 SQL 이 출력하는 오프셋은 MIN(id) - 1 이다. idInTest 가 1-base 이던 시절의 규약이라
  // 0-base 인 iterationInTest 에서는 +1 이 필요하다. 이걸 놓치면 코호트 전체가 한 칸 밀려
  // 마지막 한 건이 404 가 된다.
  const userId = USER_ID_MIN + 1 + idx;
  const enqueued = enqueue(userId);
  const waitingToken = jsonField(enqueued, 'result.waiting_token');
  if (!waitingToken) {
    // 상태 코드를 태그로 남긴다 — 진입 실패가 게이트웨이 거절(4xx)인지 포화(5xx·0)인지가
    // 갈리는데, 서버에 enqueue 결과 카운터가 없어 이 태그가 그것을 가르는 유일한 수단이다.
    enqueueFailed.add(1, { status: String(enqueued.status) });
    return;
  }

  const startedAt = Date.now();
  let entryToken = null;
  let polls = 0;

  // 진입 응답도 다음 폴링 주기를 지시한다. 이걸 무시하고 곧장 폴링하면 램프 구간의 1만 VU 가
  // 진입 직후 한 번씩 더 두드려, 서버가 주기를 지시하는 의미가 그 구간에서만 사라진다.
  sleep(nextPollSeconds(enqueued));

  while (polls < QUEUE_MAX_POLLS) {
    const res = pollStatus(waitingToken);
    polls++;

    entryToken = jsonField(res, 'result.entry_token');
    if (entryToken) {
      break;
    }
    sleep(nextPollSeconds(res));
  }

  pollsPerUser.add(polls);
  // jsonField 는 본문이 없을 때만 null 을 주고, 경로가 없으면 undefined 를 그대로 돌려준다. 대기 중
  // 응답에는 entry_token 이 아예 없으므로(NON_NULL) `!== null` 로 판정하면 미승급 VU 가 전부 승급으로
  // 집계돼 이 Rate 가 항상 100% 가 된다 — 회차 판정 지표 하나가 무조건 통과하는 상태가 된다.
  admitted.add(Boolean(entryToken));

  if (!entryToken) {
    pollsExhausted.add(1);
    return;
  }
  waitToAdmit.add((Date.now() - startedAt) / 1000);

  const admittedAt = Date.now();
  const seatId = seatFor(idx);

  // 승급 후 여정 ① 좌석맵. gzip 을 빼면 응답이 수백 KB 라 회선을 재게 된다(#505 · #348).
  const map = http.get(SEATMAP_URL, {
    headers: {
      Authorization: `Bearer ${signAccessToken(userId)}`,
      'Accept-Encoding': 'gzip',
    },
    tags: { name: 'queue_seatmap' },
  });
  seatmapDuration.add(map.timings.duration);
  seatmapOk.add(map.status === 200);

  // 승급 후 여정 ②③ SSE 구독 체류 + 좌석 선점(= 예매 생성). 대조 회차는 구독을 건너뛴다.
  const res = QUEUE_SSE_ENABLED
    ? dwellAndBook(userId, seatId, entryToken)
    : bookSeat(userId, seatId, entryToken);
  // 403 이 나오면 게이트가 입장 토큰을 거절한 것이다 — 승급 직후라 0 이어야 정상이고, 0 이 아니면
  // 입장 토큰 TTL(5m)보다 폴링→예매 지연이 길었다는 뜻이다.
  bookingForbidden.add(res.status === 403);
  const ok = res.status === 200 || res.status === 201;
  bookingOk.add(ok);

  postAdmitSeconds.add((Date.now() - admittedAt) / 1000);
  if (ok) {
    // 주 지표. 개시 시각이 승급 산식의 기준점이라(admittedThreshold = 경과 x rate) 이 값이
    // 곧 '대기열이 열린 뒤 이 사람이 예매를 마치기까지' 다. max 가 전원 소화 시간이다.
    drainSeconds.add((Date.now() - data.openedAtMs) / 1000);
  }
}

// 좌석은 비가역 소모라 사람마다 다른 좌석을 노린다. 경합 자체는 이 회차의 관측 대상이 아니다
// (오버셀 0건은 런북 §13.4-(7)(a1) 이 회차 후에 확인한다).
//
// user id 가 아니라 iteration 번호로 매긴다 — 좌석과 계정은 서로 다른 AUTO_INCREMENT 라 prod 에서
// 번호대가 겹치지 않는다. 두 오프셋이 각자 자기 범위의 시작점을 가리키고, iteration 번호가 둘을
// 1:1 로 잇는다. userId 와 같은 +1 규약을 쓴다.
function seatFor(idx) {
  return SEAT_ID_MIN + 1 + idx;
}
