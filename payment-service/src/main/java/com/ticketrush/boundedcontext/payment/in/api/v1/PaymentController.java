package com.ticketrush.boundedcontext.payment.in.api.v1;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentConfirmRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentConfirmResponse;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentDetailResponse;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentSummaryResponse;
import com.ticketrush.boundedcontext.payment.app.facade.PaymentFacade;
import com.ticketrush.boundedcontext.payment.in.api.v1.swagger.PaymentConfirmApiResponses;
import com.ticketrush.boundedcontext.payment.in.api.v1.swagger.PaymentDetailApiResponses;
import com.ticketrush.boundedcontext.payment.in.api.v1.swagger.PaymentListApiResponses;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.security.CustomUserDetails;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payment", description = "결제 관련 API")
@Validated
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

  @Operation(
      summary = "결제 내역 목록 조회",
      description = "로그인 사용자의 결제 내역을 오프셋 페이징으로 조회한다. COMPLETED 상태만 노출된다.")
  @PaymentListApiResponses
  @GetMapping
  public ResponseEntity<ApiResponse<List<PaymentSummaryResponse>>> getPayments(
      @AuthenticationPrincipal CustomUserDetails user,
      @ParameterObject @PageableDefault(size = 10, sort = "paidAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    Page<PaymentSummaryResponse> payments = paymentFacade.getPayments(user.getUserId(), pageable);
    return ApiResponse.onSuccess(SuccessStatus.OK, payments);
  }

  @Operation(
      summary = "결제 내역 단건 조회",
      description = "결제 ID로 본인 결제 단건을 조회한다. 본인 결제가 아니거나 존재하지 않으면 PAYMENT_NOT_FOUND를 반환한다.")
  @PaymentDetailApiResponses
  @GetMapping("/{paymentId}")
  public ResponseEntity<ApiResponse<PaymentDetailResponse>> getPayment(
      @AuthenticationPrincipal CustomUserDetails user,
      @Parameter(description = "결제 ID") @Positive @PathVariable Long paymentId) {
    PaymentDetailResponse response = paymentFacade.getPayment(user.getUserId(), paymentId);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }
}
