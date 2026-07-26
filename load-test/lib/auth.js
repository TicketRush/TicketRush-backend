import http from 'k6/http';
import { check, fail } from 'k6';
import { BASE_URL } from '../config/env.js';

// 부하테스트용 계정으로 로그인해 access token을 받는다: POST /api/v1/auth/login → access token.
// 응답은 ApiResponse 래핑 + 앱 ObjectMapper가 전역 snake_case라 result.access_token 으로 꺼낸다.
// 시나리오 setup()에서 1회만 호출할 것(VU마다 로그인하면 auth가 병목처럼 왜곡된다).
export function login(email, password) {
  const res = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({ email, password }), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'login 200': (r) => r.status === 200 });

  const token = res.json('result.access_token');
  // 토큰 없이 진행하면 전 VU가 Bearer undefined 로 401을 찍어, 부하가 아니라 인증 실패를 측정하게 된다.
  // setup()에서 1회 호출되므로 여기서 즉시 죽는 편이 측정 시간(=AWS 과금 시간)을 아낀다.
  if (!token) {
    fail(
      `login failed (status=${res.status}, email=${email}). ` +
        `비밀번호 env(LOAD_USER_PASSWORD / LOAD_ADMIN_PASSWORD)를 -e 로 주입했는지, ` +
        `해당 계정을 시딩했는지(seed_load.sql / seed_entry.sql) 확인.`,
    );
  }
  return token;
}
