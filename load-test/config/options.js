import { VUS, RAMP, STEADY } from './env.js';

// 전 시나리오 공통 옵션. 시나리오가 spread 후 필요한 필드만 덮어쓴다.
export const baseOptions = {
  stages: [
    { duration: RAMP, target: VUS }, // 램프업
    { duration: STEADY, target: VUS }, // 정상 부하
    { duration: RAMP, target: 0 }, // 램프다운
  ],
  thresholds: {
    // ponytail: 초기 기준값. 실측 후 read/write 시나리오별로 조정(read는 더 타이트하게).
    http_req_duration: ['p(95)<800'],
    http_req_failed: ['rate<0.01'],
  },
};
