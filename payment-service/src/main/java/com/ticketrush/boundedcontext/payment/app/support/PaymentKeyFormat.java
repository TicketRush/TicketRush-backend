package com.ticketrush.boundedcontext.payment.app.support;

import java.util.regex.Pattern;

/**
 * PG {@code paymentKey}의 형식 검증 규칙(SSOT).
 *
 * <p>비인증 webhook 엔드포인트가 요청마다 Toss 조회 API를 호출하는 증폭(amplification) 벡터를 완화하고(#357), confirm 요청의 무의미한
 * 입력을 앞단에서 거르기 위한(#413) 공용 형식 필터다. Toss는 paymentKey 최대 길이를 200자로 규정하지만 문자셋은 공식 규정이 없어(orderId만 규정),
 * 좁은 화이트리스트는 정상 요청을 오탐 기각할 수 있다. 따라서 제어문자/공백/멀티바이트만 배제하는 넓은 인쇄가능 ASCII(0x21~0x7E, 1~200자)로 잡아 실제
 * Toss 키는 모두 통과시키고 가비지만 거른다.
 *
 * <p>webhook(UseCase)은 {@link #PATTERN}으로 요청마다 매칭하고, confirm DTO는 {@code @Pattern(regexp = REGEX)}로
 * 검증한다. {@code @Pattern}의 인자는 컴파일타임 상수 문자열이어야 하므로 {@link #REGEX}가 원본이고 {@link #PATTERN}은 이를 프리컴파일한
 * 파생값이다(값 이중 정의 방지).
 *
 * <p>주의: 상한(200자·인쇄가능 ASCII)의 근거는 Toss 스펙이다. 현재 실제 결제 승인 구현체는 Toss뿐이라(다른 provider는 라우터에서 {@code
 * PAYMENT_PROVIDER_NOT_SUPPORTED}로 선차단) confirm에 이 규칙을 적용해도 정상 결제가 오탐 기각될 여지가 없다. 향후 Toss 외 PG 승인
 * 연동이 추가되면, 해당 PG의 paymentKey 형식이 이 화이트리스트에 부합하는지 재검토해야 한다.
 */
public final class PaymentKeyFormat {

  /** 인쇄가능 ASCII(0x21~0x7E) 1~200자. {@code @Pattern(regexp = ...)}에 그대로 쓰이는 원본 정규식. */
  public static final String REGEX = "[\\x21-\\x7E]{1,200}";

  /** {@link #REGEX}를 프리컴파일한 패턴. 요청마다 매칭하는 호출부(webhook)에서 재컴파일을 피하기 위해 노출한다. */
  public static final Pattern PATTERN = Pattern.compile(REGEX);

  private PaymentKeyFormat() {}
}
