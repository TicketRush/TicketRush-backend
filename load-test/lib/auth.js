import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../config/env.js';

// DevToken 발급(local/dev 프로파일 전용): POST /api/v1/dev/auth/token → access token.
// 앱의 ObjectMapper가 전역 snake_case라 요청 필드는 user_id, 응답은 result.access_token 이다.
// 시나리오 setup()에서 1회만 호출할 것(VU마다 발급하면 auth가 병목처럼 왜곡된다).
export function devToken(userId) {
  const res = http.post(`${BASE_URL}/api/v1/dev/auth/token`, JSON.stringify({ user_id: userId }), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'devToken 200': (r) => r.status === 200 });
  return res.json('result.access_token');
}
