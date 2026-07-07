package com.ticketrush.boundedcontext.payment.in.api.v1;

import com.ticketrush.boundedcontext.payment.app.facade.PaymentFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PG 결제 Webhook 수신 컨트롤러.
 *
 * <p>게이트웨이를 거치지 않는 외부 PG 직접 호출 경로다(SecurityConfig에서 permitAll). 인증은 paymentKey 재조회 검증으로 수행하며, 파싱 전
 * 원문 body를 그대로 받는다. PG가 재전송하지 않도록 정상 처리 시 200을 반환한다.
 */
@Tag(name = "Payment", description = "결제 관련 API")
@RestController
@RequestMapping("/api/v1/payment")
@RequiredArgsConstructor
public class PaymentWebhookController {

  private final PaymentFacade paymentFacade;

  @Operation(
      summary = "PG 결제 Webhook 수신",
      description =
          "PG사가 보내는 비동기 결제 알림을 수신한다. paymentKey로 PG에 결제를 재조회해 진위를 검증한 뒤 기존 결제를 찾아 멱등 처리한다. "
              + "위조/페이로드 검증 실패 시에만 4xx, 그 외에는 재전송 방지를 위해 200을 반환한다.")
  @PostMapping("/webhook")
  public ResponseEntity<Void> webhook(@RequestBody byte[] rawBody) {
    paymentFacade.handleWebhook(rawBody);
    return ResponseEntity.ok().build();
  }
}
