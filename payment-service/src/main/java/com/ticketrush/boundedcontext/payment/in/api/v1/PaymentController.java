package com.ticketrush.boundedcontext.payment.in.api.v1;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentConfirmRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentConfirmResponse;
import com.ticketrush.boundedcontext.payment.app.facade.PaymentFacade;
import com.ticketrush.boundedcontext.payment.in.api.v1.swagger.PaymentConfirmApiResponses;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.security.CustomUserDetails;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payment", description = "결제 관련 API")
@RestController
@RequestMapping("/api/v1/payment")
@RequiredArgsConstructor
public class PaymentController {

  private final PaymentFacade paymentFacade;

  @Operation(
      summary = "결제 Confirm",
      description =
          "PG사 결제 인증 후 넘어온 데이터를 검증하고 결제를 확정한다. "
              + "성공 시 PaymentConfirmedEvent를 발행하여 후속 도메인이 처리하도록 한다.")
  @PaymentConfirmApiResponses
  @PostMapping("/confirm")
  public ResponseEntity<ApiResponse<PaymentConfirmResponse>> confirm(
      @AuthenticationPrincipal CustomUserDetails user,
      @Valid @RequestBody PaymentConfirmRequest request) {
    PaymentConfirmResponse response = paymentFacade.confirm(user.getUserId(), request);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }
}
