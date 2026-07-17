package com.ticketrush.boundedcontext.payment.app.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * PG(Toss) 결제 Webhook 페이로드 매핑 (필요 필드만).
 *
 * <p>Toss는 결제 상태 변경 시 {@code { "eventType": "...", "data": { "paymentKey": "...", ... } }} 형태로 비동기
 * 알림을 보낸다. 외부 PG가 보내는 camelCase JSON이므로, 전역 snake_case 매퍼와 분리해 camelCase 기본 매핑을 쓰는 별도 매퍼({@code
 * PaymentWebhookUseCase}의 {@code tools.jackson} JsonMapper)로 역직렬화한다.
 *
 * <p>참고: https://docs.tosspayments.com/reference/webhook-event
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentWebhookRequest(String eventType, Data data) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(String paymentKey, String orderId, String status) {}

  /** data 내부의 paymentKey를 안전하게 꺼낸다(data 누락 시 null). */
  public String paymentKey() {
    return data == null ? null : data.paymentKey();
  }

  /** data 내부의 orderId를 안전하게 꺼낸다(data 누락 시 null). */
  public String orderId() {
    return data == null ? null : data.orderId();
  }

  /** data 내부의 status를 안전하게 꺼낸다(data 누락 시 null). */
  public String status() {
    return data == null ? null : data.status();
  }
}
