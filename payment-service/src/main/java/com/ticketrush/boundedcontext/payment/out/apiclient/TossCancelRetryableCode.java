package com.ticketrush.boundedcontext.payment.out.apiclient;

import java.util.Arrays;

/**
 * Toss 결제 취소 4xx 응답 중 <b>재시도하면 성공할 수 있는</b> 원본 {@code code} 화이트리스트(#573).
 *
 * <p><b>왜 HTTP 상태로 가르지 않는가</b>: 같은 상태 코드 안에 성격이 다른 실패가 섞여 있다. 403 하나만 봐도 재시도 대상({@link
 * #FORBIDDEN_CONSECUTIVE_REQUEST}), 결정적 거절({@code NOT_CANCELABLE_PAYMENT}), 설정 오류({@code
 * INCORRECT_BASIC_AUTH_FORMAT})가 공존한다. 400도 마찬가지다({@link #PROVIDER_ERROR} vs {@code
 * ALREADY_CANCELED_PAYMENT}). 그래서 판정 근거를 PG 원본 code로 둔다.
 *
 * <p><b>왜 화이트리스트인가</b>: 여기 없는 코드는 전부 {@code PAYMENT_REFUND_FAILED}로 떨어져 <b>기존 동작을 그대로 유지</b>한다 —
 * FAILED 환불 이력이 남고 {@code RefundFailedEvent}가 booking의 {@code refundFailedAt}을 채워 관리자 재환불 API로 복구할
 * 수 있다. 반대로 블랙리스트(미정의를 재시도로 보내는 방식)를 쓰면 Toss가 새 거절 코드를 추가할 때마다 그 건이 이력 없이 사라져 복구 경로가 닫힌다. 미정의 코드가
 * 실제로 재시도 대상이었다면 손해는 "자동 복구 못 함"에 그치지만, 반대 방향의 손해는 "과금된 건을 잃음"이다.
 *
 * <p><b>HTTP 429는 매핑하지 않는다</b>: Toss 에러 코드 문서 전체에 429를 쓰는 항목이 없음을 확인했다(2026-08-06). rate limit 자리는
 * 403 {@link #FORBIDDEN_CONSECUTIVE_REQUEST}가 대신한다. #573 완료 조건이 요구한 "429 미사용 확인"의 근거가 이 문단이다.
 *
 * <p><b>인증 실패(401 {@code UNAUTHORIZED_KEY}, 403 {@code INCORRECT_BASIC_AUTH_FORMAT})를 넣지 않은 근거</b>:
 * 재시도로 해소되지 않으므로 여기 넣을 수는 없다. 그렇다고 {@code PAYMENT_PG_AUTH_FAILED}로 분리해 재시도→DLT로 보내지도 않는다. DLT는 복구
 * 창구가 아니기 때문이다 — 재처리 수단이 없고 {@code dead_letter_record}는 30일 뒤 삭제되며(DltMonitorProperties), 그 건들은
 * {@code refundFailedAt}이 없어 관리자 재환불 API가 {@code BOOKING_REFUND_RETRY_NOT_ALLOWED}로 거부한다. 현행대로
 * FAILED 이력을 남기면 키를 고친 뒤 미해결 목록에서 일괄 재환불할 수 있다. 원인 구분은 아래 재시도 로그의 {@code tossCode}가 맡는다. 상세는 ADR
 * 0012.
 *
 * <p>참고: <a href="https://docs.tosspayments.com/reference/error-codes">API 에러 코드</a>, <a
 * href="https://docs.tosspayments.com/reference/using-api/idempotency-key">멱등키</a>
 */
public enum TossCancelRetryableCode {

  /**
   * 409 · "이전 멱등 요청이 처리중입니다."
   *
   * <p>Toss 문서가 "이 에러가 돌아오면 다시 한번 요청해서 응답을 확인하세요"라고 직접 안내하는 코드다. 우리는 결제당 고정 멱등 키({@code
   * REFUND-%07d})를 쓰므로 같은 결제에 환불 요청이 겹치면 정확히 이 응답이 나온다. 선행 요청이 끝나면 재요청은 멱등 응답으로 성공한다.
   */
  IDEMPOTENT_REQUEST_PROCESSING,

  /**
   * 400 · "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
   *
   * <p><b>한계</b>: Toss 문서는 <b>실패 응답이 멱등키에 캐시되는지를 명시하지 않는다.</b> 캐시된다면 같은 멱등 키로 재요청해도 같은 400이 돌아와
   * 재시도가 무효가 된다. 그럼에도 포함하는 이유는 실패 모드가 안전하기 때문이다 — 무효라면 대가는 대기 1회와 PG 호출 1회뿐이고 결과는 기존과 동일하게 FAILED
   * 이력 + 보상으로 확정된다. 실제 효과는 {@code ticketrush.payment.pg.cancel.retry}의 {@code outcome} 태그로 사후에
   * 판정한다.
   */
  PROVIDER_ERROR,

  /**
   * 403 · "반복적인 요청은 허용되지 않습니다. 잠시 후 다시 시도해주세요."
   *
   * <p>Toss에 429가 없으므로 이 코드가 사실상 rate limit이다. 그리고 이게 가장 나오기 쉬운 구간이 하필 #492가 겨냥한 대량 만료 버스트다 — 좌석 확정
   * 실패가 동시다발로 터지면 보상 환불도 동시다발로 나간다.
   */
  FORBIDDEN_CONSECUTIVE_REQUEST;

  /**
   * 주어진 Toss 원본 code가 재시도 대상인지 판정한다.
   *
   * <p><b>같은 PG의 code를 해석하는 분류기가 둘이며 규칙이 서로 다르다.</b> 승인 경로의 {@link TossErrorCode}는 <i>가장 긴
   * prefix</i> 매칭에 {@code UNKNOWN} 폴백이고, 취소 경로인 이쪽은 <i>정확 일치</i>에 {@code false} 폴백이다. 통합하지 않은 이유는
   * <b>폴백이 승인 전용</b>이기 때문이다 — {@code TossErrorCode}의 {@code UNKNOWN}은 {@code
   * PAYMENT_APPROVAL_FAILED}로 떨어지므로, 취소에 그대로 재사용하면 미정의 거절이 {@code isDeterministicRejection} false가
   * 되어 FAILED 이력과 보상이 통째로 사라진다. <b>합치려 한다면 이 함정을 먼저 해소해야 한다.</b>
   *
   * <p>참고로 두 분류기가 같은 code를 다르게 해석하는 사례는 현재 <b>없다</b>. {@code FORBIDDEN_CONSECUTIVE_REQUEST}는 승인 경로의
   * {@code FORBIDDEN_REQUEST} prefix에 걸리지 않아({@code FORBIDDEN_C} ≠ {@code FORBIDDEN_R}) 거기서도 {@code
   * UNKNOWN}으로 떨어진다.
   *
   * <p>매칭을 정확 일치로 둔 것은 화이트리스트가 세 개뿐이라 prefix 매칭이 줄 이득이 없고, 정확 일치가 "여기 적힌 것만 재시도한다"는 계약을 그대로 드러내기
   * 때문이다.
   */
  public static boolean isRetryable(String code) {
    if (code == null || code.isBlank()) {
      return false;
    }
    return Arrays.stream(values()).anyMatch(value -> value.name().equals(code));
  }
}
