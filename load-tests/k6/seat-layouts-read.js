import http from 'k6/http';
import { check, sleep } from 'k6';

const TARGET_URL = __ENV.TARGET_URL;
const AUTH_TOKEN = __ENV.AUTH_TOKEN || '';

const VUS = Number.parseInt(__ENV.VUS || '20', 10);
const DURATION = __ENV.DURATION || '2m';
const PAUSE_SECONDS = Number.parseFloat(__ENV.PAUSE_SECONDS || '1');
const EXPECTED_SEATS = Number.parseInt(__ENV.EXPECTED_SEATS || '120', 10);

if (!TARGET_URL) {
  throw new Error('TARGET_URL is required.');
}

if (!Number.isInteger(VUS) || VUS <= 0) {
  throw new Error('VUS must be a positive integer.');
}

if (!Number.isInteger(EXPECTED_SEATS) || EXPECTED_SEATS <= 0) {
  throw new Error('EXPECTED_SEATS must be a positive integer.');
}

if (!Number.isFinite(PAUSE_SECONDS) || PAUSE_SECONDS < 0) {
  throw new Error('PAUSE_SECONDS must be zero or greater.');
}

export const options = {
  scenarios: {
    seat_layouts_read: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      gracefulStop: '10s',
      tags: {
        test_type: 'seat-layouts-read',
      },
    },
  },

  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<800'],
    checks: ['rate==1'],
  },

  summaryTrendStats: [
    'avg',
    'min',
    'med',
    'max',
    'p(90)',
    'p(95)',
    'p(99)',
  ],
};

export default function () {
  const headers = {
    Accept: 'application/json',
    'User-Agent': 'TicketRush-k6-seat-layouts/1.0',
  };

  if (AUTH_TOKEN) {
    headers.Authorization = `Bearer ${AUTH_TOKEN}`;
  }

  const response = http.get(TARGET_URL, {
    headers,
    timeout: '10s',
    tags: {
      endpoint: 'seat-layouts',
    },
  });

  let payload = null;

  try {
    payload = response.json();
  } catch {
    payload = null;
  }

  check(response, {
    'status is 200': (res) => res.status === 200,
    'API response is successful': () => payload?.is_success === true,
    [`result contains ${EXPECTED_SEATS} seats`]: () =>
      Array.isArray(payload?.result) &&
      payload.result.length === EXPECTED_SEATS,
  });

  sleep(PAUSE_SECONDS);
}
