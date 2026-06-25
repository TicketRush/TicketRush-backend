package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 요청 provider를 담당하는 {@link PaymentApprovalClient}로 결제 승인을 위임한다.
 *
 * <p>실제 provider 구현체는 {@link PaymentApprovalClient#provider()}를 키로 Map에 등록해 O(1)로 조회하고, 매칭되는 구현체가
 * 없으면 {@link PaymentApprovalClient#isFallback()} 인 stub으로만 대체한다. 둘 다 없으면 {@link
 * ErrorStatus#PAYMENT_PROVIDER_NOT_SUPPORTED}를 던진다.
 */
@Component
public class PaymentApprovalClientRouter {

  private final Map<PaymentProvider, PaymentApprovalClient> clientsByProvider;
  private final PaymentApprovalClient fallbackClient;

  public PaymentApprovalClientRouter(List<PaymentApprovalClient> clients) {
    this.clientsByProvider =
        clients.stream()
            .filter(client -> !client.isFallback())
            .collect(
                Collectors.toUnmodifiableMap(PaymentApprovalClient::provider, Function.identity()));
    this.fallbackClient =
        clients.stream().filter(PaymentApprovalClient::isFallback).findFirst().orElse(null);
  }

  public PaymentApprovalResponse approve(PaymentApprovalRequest request) {
    return resolve(request.provider()).approve(request);
  }

  private PaymentApprovalClient resolve(PaymentProvider provider) {
    PaymentApprovalClient client = clientsByProvider.get(provider);
    if (client != null) {
      return client;
    }
    if (fallbackClient != null) {
      return fallbackClient;
    }
    throw new BusinessException(ErrorStatus.PAYMENT_PROVIDER_NOT_SUPPORTED);
  }
}
