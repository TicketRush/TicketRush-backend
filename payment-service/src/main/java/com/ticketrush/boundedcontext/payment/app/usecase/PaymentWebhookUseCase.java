package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentWebhookRequest;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentInquiryClientRouter;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentInquiryResult;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * PG 결제 Webhook 수신 UseCase.
 *
 * <p>Webhook은 confirm 응답이 유실됐을 때를 대비한 최후의 보루다. Toss는 결제 상태 변경 webhook에 서명 헤더를 보내지 않으므로, webhook
 * body를 신뢰하는 대신 {@code paymentKey}로 PG 결제 조회 API를 재호출해 진위를 검증한다(위조 불가한 paymentKey + secret-key 인증된
 * 조회). PG에 존재하면 진짜 webhook으로 보고 기존 Payment를 찾아 멱등 처리한다.
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

  private final PaymentInquiryClientRouter inquiryClientRouter;
  private final PaymentRepository paymentRepository;

  /* 외부 PG가 보내는 camelCase 페이로드를 파싱한다. 앱 전역 매퍼는 snake_case로 설정돼 있어 webhook 파싱에 맞지
   * 않으므로, camelCase 기본 매핑을 쓰는 전용 매퍼를 둔다(프로젝트 표준인 Jackson3 tools.jackson 사용). */
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  public void handle(byte[] rawBody) {
    PaymentWebhookRequest request = parse(rawBody);
    String paymentKey = request.paymentKey();
    if (!StringUtils.hasText(paymentKey)) {
      // 구독 대상이 아닌 이벤트(또는 paymentKey가 없는 이벤트)는 처리할 수 없으므로 무시한다(PG 재전송 방지를 위해 200).
      log.info("[PG-WEBHOOK] 처리 대상이 아닌 이벤트입니다. 무시합니다. eventType={}", request.eventType());
      return;
    }

    // 재조회 검증: PG에 실제 존재하는 결제인지 확인한다. 존재하지 않으면 위조/무효 webhook으로 거부하고,
    // 통신 실패는 진위를 판정할 수 없으므로 예외를 전파해(non-200) PG 재전송에 위임한다.
    // provider를 TOSS로 고정하는 것은 의도된 것이다: 이 webhook 엔드포인트는 Toss 전용(payload도 Toss
    // 포맷)이라 payload에서 provider를 도출할 필요가 없고, 라우터는 다중 provider 라우팅이 아니라 환경별
    // 빈 유무(prod=Toss/local=stub/미설정=없음)에 따른 기동 안전성을 위해 쓴다. 다른 PG webhook이 생기면
    // 전용 엔드포인트를 두는 것이 자연스럽다.
    Optional<PaymentInquiryResult> inquiry =
        inquiryClientRouter.inquire(PaymentProvider.TOSS, paymentKey);
    if (inquiry.isEmpty()) {
      log.warn(
          "[PG-WEBHOOK] PG에 존재하지 않는 paymentKey입니다. 위조/무효 webhook으로 거부합니다. paymentKey={}",
          mask(paymentKey));
      throw new BusinessException(ErrorStatus.PAYMENT_WEBHOOK_INVALID);
    }
    log.info(
        "[PG-WEBHOOK] 재조회 검증 통과. paymentKey={}, pgStatus={}",
        mask(paymentKey),
        inquiry.get().status());

    paymentRepository
        .findByPaymentKey(paymentKey)
        .ifPresentOrElse(
            payment -> {
              if (payment.isAlreadyProcessed()) {
                log.info(
                    "[PG-WEBHOOK] 이미 확정된 결제입니다. 멱등 처리합니다. paymentKey={}, paymentId={}",
                    mask(paymentKey),
                    payment.getId());
              } else {
                log.warn(
                    "[PG-WEBHOOK] Payment가 있으나 확정 상태가 아닙니다. paymentKey={}, paymentId={}, status={}",
                    mask(paymentKey),
                    payment.getId(),
                    payment.getStatus());
              }
            },
            () ->
                log.error(
                    "[CRITICAL][PG-WEBHOOK] PG 승인 webhook을 수신했으나 해당 Payment가 없습니다. "
                        + "결제는 승인됐으나 서버 저장에 실패한 상태일 수 있어 수동 확인이 필요합니다. "
                        + "paymentKey={}, orderId={}, status={}",
                    mask(paymentKey),
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

  /** 로그 노출 시 민감한 paymentKey를 앞 4자리만 남기고 마스킹한다. */
  private String mask(String value) {
    if (value == null || value.length() <= 4) {
      return "****";
    }
    return value.substring(0, 4) + "****";
  }
}
