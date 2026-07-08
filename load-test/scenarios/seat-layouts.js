// (b) 좌석 조회 핫패스 — 인증 불필요. read 시나리오.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, PERF_ID } from '../config/env.js';
import { baseOptions } from '../config/options.js';

export const options = baseOptions;

export default function () {
  const res = http.get(`${BASE_URL}/api/v1/seat/${PERF_ID}/seat-layouts`);
  check(res, { 'seat-layouts 200': (r) => r.status === 200 });
}
