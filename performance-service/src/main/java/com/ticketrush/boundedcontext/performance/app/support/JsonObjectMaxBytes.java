package com.ticketrush.boundedcontext.performance.app.support;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * null은 허용하되, 값이 있으면 JSON <b>객체</b>이고 compact 직렬화(공백 없음) 기준 UTF-8 바이트 수가 {@code max} 이하이며 중첩 깊이가
 * {@code maxDepth} 이하여야 하는 검증 어노테이션(#650).
 *
 * <p>내용(키·값)은 검사하지 않는다. 캐릭터 구성 JSON의 스키마는 프론트가 소유하며 백엔드는 해석하지 않는다는 것이 #650의 결정이다. 여기서 막는 것은 "객체가 아닌
 * 값"(문자열·배열·숫자)과 크기, 그리고 중첩 깊이뿐이다. 깊이를 보는 이유는 MySQL 8 JSON 컬럼이 깊이 100 초과 문서를 저장 단계에서 거절하기 때문이다 —
 * 101단 객체는 500바이트 남짓이라 크기 검증만으로는 통과해 500이 된다.
 *
 * <p>바이트 수는 {@code JsonNode.toString()}(compact)의 UTF-8 길이로 센다. 클라이언트가 보낸 원문의 공백·줄바꿈은 세지 않으므로, 프론트는
 * {@code JSON.stringify(config)}의 UTF-8 길이로 같은 값을 미리 확인할 수 있다.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = JsonObjectMaxBytesValidator.class)
public @interface JsonObjectMaxBytes {

  /** 허용 최대 바이트 수(compact 직렬화 UTF-8 기준, 이 값까지 허용). */
  int max();

  /** 허용 최대 중첩 깊이(최상위 객체가 1, 이 값까지 허용). */
  int maxDepth();

  String message() default "JSON 객체여야 하며 {max}바이트·중첩 {maxDepth}단을 초과할 수 없습니다.";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
