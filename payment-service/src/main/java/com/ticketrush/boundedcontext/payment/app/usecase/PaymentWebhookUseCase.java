package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentWebhookRequest;
import com.ticketrush.boundedcontext.payment.app.support.PaymentWebhookVerifier;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * PG 결제 Webhook 수신 UseCase.
 *
 * <p>Webhook은 confirm 응답이 유실됐을 때를 대비한 최후의 보루다. 서명을 검증한 뒤 {@code paymentKey}로 기존 Payment를 조회해 멱등
 * 처리한다.
 *
 * <p>Toss webhook 페이로드에는 {@code seatId}가 없고 후속 컨슈머({@code PaymentConfirmedEventListener})는 seatId를
 * 필수로 사용하므로, webhook 단독으로는 Payment를 안전하게 생성할 수 없다. 따라서 정상 케이스(confirm이 서버에서 성공했으나 응답만 유실)는 기존
 * Payment를 찾아 멱등 처리하고, Payment가 없는 극단 케이스(PG 승인 후 저장 전 유실)는 즉시 생성하지 않고 {@code [CRITICAL]} 로그로 수동
 * 복구가 가능하도록 남긴다(데이터 유실 추적, #90).
 *
 * <p>읽기 전용이라 트랜잭션을 두지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWebhookUseCase {

  private final PaymentWebhookVerifier webhookVerifier;
  private final PaymentRepository paymentRepository;

  /* 외부 PG가 보내는 camelCase 페이로드를 파싱한다. 앱 전역 매퍼는 snake_case로 설정돼 있어 webhook 파싱에 맞지
   * 않으므로, camelCase 기본 매핑을 쓰는 전용 매퍼를 둔다(프로젝트 표준인 Jackson3 tools.jackson 사용). */
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  public void handle(byte[] rawBody, String signature) {
    webhookVerifier.verify(rawBody, signature);

    PaymentWebhookRequest request = parse(rawBody);
    String paymentKey = request.paymentKey();
    if (!StringUtils.hasText(paymentKey)) {
      // 구독 대상이 아닌 이벤트(또는 paymentKey가 없는 이벤트)는 처리할 수 없으므로 무시한다(PG 재전송 방지를 위해 200).
      log.info("[PG-WEBHOOK] 처리 대상이 아닌 이벤트입니다. 무시합니다. eventType={}", request.eventType());
      return;
    }

    paymentRepository
        .findByPaymentKey(paymentKey)
        .ifPresentOrElse(
            payment -> {
              if (payment.isAlreadyProcessed()) {
                log.info(
                    "[PG-WEBHOOK] 이미 확정된 결제입니다. 멱등 처리합니다. paymentKey={}, paymentId={}",
                    paymentKey,
                    payment.getId());
              } else {
                log.warn(
                    "[PG-WEBHOOK] Payment가 있으나 확정 상태가 아닙니다. paymentKey={}, paymentId={}, status={}",
                    paymentKey,
                    payment.getId(),
                    payment.getStatus());
              }
            },
            () ->
                log.error(
                    "[CRITICAL][PG-WEBHOOK] PG 승인 webhook을 수신했으나 해당 Payment가 없습니다. "
                        + "결제는 승인됐으나 서버 저장에 실패한 상태일 수 있어 수동 확인이 필요합니다. "
                        + "paymentKey={}, orderId={}, status={}",
                    paymentKey,
                    request.orderId(),
                    request.status()));
  }

  private PaymentWebhookRequest parse(byte[] rawBody) {
    try {
      return jsonMapper.readValue(rawBody, PaymentWebhookRequest.class);
    } catch (Exception e) {
      log.warn("[PG-WEBHOOK] 페이로드 파싱에 실패했습니다. message={}", e.getMessage());
      throw new BusinessException(ErrorStatus.PAYMENT_WEBHOOK_PAYLOAD_INVALID, e);
    }
  }
}
