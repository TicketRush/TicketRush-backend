import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../config/env.js';

// 부하테스트용 계정으로 로그인해 access token을 받는다: POST /api/v1/auth/login → access token.
// 응답은 ApiResponse 래핑 + 앱 ObjectMapper가 전역 snake_case라 result.access_token 으로 꺼낸다.
// 시나리오 setup()에서 1회만 호출할 것(VU마다 로그인하면 auth가 병목처럼 왜곡된다).
export function login(email, password) {
  const res = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({ email, password }), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'login 200': (r) => r.status === 200 });
  return res.json('result.access_token');
}
