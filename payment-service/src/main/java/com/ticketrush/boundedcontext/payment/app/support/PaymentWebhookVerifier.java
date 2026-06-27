package com.ticketrush.boundedcontext.payment.app.support;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * PG Webhook 서명 검증기.
 *
 * <p>Webhook은 게이트웨이를 거치지 않는 외부 PG 직접 호출이므로, 서명 검증이 유일한 인증 수단이다. 시크릿으로 원문 body의 HMAC-SHA256을 재계산해
 * 헤더로 전달된 서명과 상수시간 비교한다. 검증 실패 시 {@link ErrorStatus#PAYMENT_WEBHOOK_SIGNATURE_INVALID}를 던진다.
 *
 * <p>시크릿이 설정되지 않은 환경에서는 모든 webhook을 거부한다(fail-closed).
 */
@Slf4j
@Component
public class PaymentWebhookVerifier {

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final String webhookSecret;

  public PaymentWebhookVerifier(@Value("${payment.pg.toss.webhook-secret:}") String webhookSecret) {
    this.webhookSecret = webhookSecret;
  }

  /**
   * 원문 body와 서명을 검증한다.
   *
   * @param rawBody 수신한 원문 바이트 (PG가 서명한 바이트 그대로여야 서명이 일치한다)
   * @param signature PG가 전달한 Base64 인코딩 HMAC-SHA256 서명
   * @throws BusinessException 시크릿 미설정·서명 누락·서명 불일치 시
   */
  public void verify(byte[] rawBody, String signature) {
    if (!StringUtils.hasText(webhookSecret)) {
      log.error("[PG-WEBHOOK] webhook-secret 미설정으로 서명을 검증할 수 없습니다. webhook을 거부합니다.");
      throw new BusinessException(ErrorStatus.PAYMENT_WEBHOOK_SIGNATURE_INVALID);
    }
    if (!StringUtils.hasText(signature)) {
      log.warn("[PG-WEBHOOK] 서명 헤더가 없습니다.");
      throw new BusinessException(ErrorStatus.PAYMENT_WEBHOOK_SIGNATURE_INVALID);
    }

    String expected = computeSignature(rawBody);
    if (!constantTimeEquals(expected, signature)) {
      log.warn("[PG-WEBHOOK] 서명이 일치하지 않습니다.");
      throw new BusinessException(ErrorStatus.PAYMENT_WEBHOOK_SIGNATURE_INVALID);
    }
  }

  private String computeSignature(byte[] rawBody) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      byte[] hash = mac.doFinal(rawBody);
      return Base64.getEncoder().encodeToString(hash);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      log.error("[PG-WEBHOOK] 서명 계산에 실패했습니다. message={}", e.getMessage());
      throw new BusinessException(ErrorStatus.PAYMENT_WEBHOOK_SIGNATURE_INVALID, e);
    }
  }

  /* 타이밍 공격을 막기 위해 길이/내용 비교를 상수시간으로 수행한다. */
  private boolean constantTimeEquals(String expected, String actual) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }
}
