package com.ticketrush.boundedcontext.payment.app.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentWebhookVerifierTest {

  private static final String SECRET = "test-webhook-secret";
  private static final String BODY = "{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{}}";

  private final PaymentWebhookVerifier verifier = new PaymentWebhookVerifier(SECRET);

  private static String sign(String secret, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("올바른 서명이면 검증을 통과한다")
  void verify_passes_with_valid_signature() throws Exception {
    // given
    String signature = sign(SECRET, BODY);

    // expect
    assertThatCode(() -> verifier.verify(BODY.getBytes(StandardCharsets.UTF_8), signature))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("body가 변조되면 서명 검증에 실패한다")
  void verify_fails_when_body_tampered() throws Exception {
    // given - 원문으로 만든 서명을 변조된 body와 함께 검증
    String signature = sign(SECRET, BODY);
    String tamperedBody = BODY.replace("PAYMENT_STATUS_CHANGED", "TAMPERED");

    // expect
    assertThatThrownBy(
            () -> verifier.verify(tamperedBody.getBytes(StandardCharsets.UTF_8), signature))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_WEBHOOK_SIGNATURE_INVALID);
  }

  @Test
  @DisplayName("다른 시크릿으로 만든 서명은 검증에 실패한다")
  void verify_fails_with_wrong_secret_signature() throws Exception {
    // given
    String signature = sign("another-secret", BODY);

    // expect
    assertThatThrownBy(() -> verifier.verify(BODY.getBytes(StandardCharsets.UTF_8), signature))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_WEBHOOK_SIGNATURE_INVALID);
  }

  @Test
  @DisplayName("서명 헤더가 없으면 검증에 실패한다")
  void verify_fails_when_signature_missing() {
    // expect
    assertThatThrownBy(() -> verifier.verify(BODY.getBytes(StandardCharsets.UTF_8), null))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_WEBHOOK_SIGNATURE_INVALID);
  }

  @Test
  @DisplayName("webhook 시크릿이 설정되지 않으면 모든 webhook을 거부한다(fail-closed)")
  void verify_fails_when_secret_not_configured() throws Exception {
    // given
    PaymentWebhookVerifier noSecret = new PaymentWebhookVerifier("");
    String signature = sign(SECRET, BODY);

    // expect
    assertThatThrownBy(() -> noSecret.verify(BODY.getBytes(StandardCharsets.UTF_8), signature))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_WEBHOOK_SIGNATURE_INVALID);
  }
}
