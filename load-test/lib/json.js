// res.json() 은 본문이 없거나 JSON 이 아니면 예외를 던지고, 그러면 iteration 이 통째로 죽는다.
// 타임아웃·연결 실패는 status=0 / body=null 로 오는데 그건 스파이크 피크에서 실제로 나올 수 있는
// 상황이다. 하필 포화를 관측해야 할 구간에서 지표가 비뚤어지므로 파싱 실패를 삼킨다.
// 이렇게 삼켜도 그 요청은 http_req_failed 에 그대로 남아 에러율에서 빠지지 않는다.
//
// entry-spike.js 가 같은 함수를 파일 안에 사적으로 갖고 있다(#402). 그쪽을 이 모듈로 바꾸지 않은 것은
// 의도적이다 — 이미 측정을 마친 시나리오라 재현성을 위해 손대지 않는다.
export function jsonField(res, path) {
  if (!res.body) {
    return null;
  }
  try {
    return res.json(path);
  } catch (e) {
    return null;
  }
}
