package com.ticketrush.boundedcontext.payment.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TossCancelRetryableCodeTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "IDEMPOTENT_REQUEST_PROCESSING",
        "PROVIDER_ERROR",
        "FORBIDDEN_CONSECUTIVE_REQUEST"
      })
  @DisplayName("화이트리스트에 열거된 코드만 재시도 대상이다")
  void retryable_codes(String code) {
    assertThat(TossCancelRetryableCode.isRetryable(code)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ALREADY_CANCELED_PAYMENT",
        "NOT_CANCELABLE_PAYMENT",
        "REFUND_REJECTED",
        "NOT_FOUND_PAYMENT",
        "UNAUTHORIZED_KEY",
        "INCORRECT_BASIC_AUTH_FORMAT",
        "SOME_UNDEFINED_TOSS_CODE"
      })
  @DisplayName("화이트리스트 밖 코드는 재시도 대상이 아니다 — 미정의 코드도 기존 동작(결정적 거절)을 유지한다")
  void non_retryable_codes(String code) {
    assertThat(TossCancelRetryableCode.isRetryable(code)).isFalse();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  @DisplayName("code가 없으면 재시도하지 않는다 — body를 읽지 못한 응답이 여기로 떨어진다")
  void blank_code_is_not_retryable(String code) {
    assertThat(TossCancelRetryableCode.isRetryable(code)).isFalse();
  }

  @Test
  @DisplayName("prefix가 겹치는 코드를 정확 일치로 구분한다")
  void matches_exactly_not_by_prefix() {
    assertThat(TossCancelRetryableCode.isRetryable("PROVIDER_ERROR_EXTRA")).isFalse();
    assertThat(TossCancelRetryableCode.isRetryable("FORBIDDEN_REQUEST")).isFalse();
  }
}
