package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 요청 provider를 담당하는 {@link PaymentInquiryClient}로 결제 조회를 위임한다.
 *
 * <p>실제 provider 구현체는 {@link PaymentInquiryClient#provider()}를 키로 Map에 등록해 O(1)로 조회하고, 매칭되는 구현체가
 * 없으면 {@link PaymentInquiryClient#isFallback()} 인 stub으로만 대체한다. 둘 다 없으면 {@link
 * ErrorStatus#PAYMENT_PROVIDER_NOT_SUPPORTED}를 던진다.
 */
@Component
public class PaymentInquiryClientRouter {

  private final Map<PaymentProvider, PaymentInquiryClient> clientsByProvider;
  private final PaymentInquiryClient fallbackClient;

  public PaymentInquiryClientRouter(List<PaymentInquiryClient> clients) {
    this.clientsByProvider =
        clients.stream()
            .filter(client -> !client.isFallback())
            .collect(
                Collectors.toUnmodifiableMap(PaymentInquiryClient::provider, Function.identity()));
    this.fallbackClient =
        clients.stream().filter(PaymentInquiryClient::isFallback).findFirst().orElse(null);
  }

  public Optional<PaymentInquiryResult> inquire(PaymentProvider provider, String paymentKey) {
    return resolve(provider).inquire(paymentKey);
  }

  // 로컬 기본값(toss·stub 모두 off)에서는 등록된 구현체가 없어 모든 조회가 PAYMENT_PROVIDER_NOT_SUPPORTED로
  // 떨어진다(빈 List 주입이라 기동은 정상). fail-closed로 안전하며 의도된 동작이다. 로컬에서 webhook 경로를
  // 검증하려면 payment.pg.stub.enabled=true로 stub을 켜야 한다.
  private PaymentInquiryClient resolve(PaymentProvider provider) {
    PaymentInquiryClient client = clientsByProvider.get(provider);
    if (client != null) {
      return client;
    }
    if (fallbackClient != null) {
      return fallbackClient;
    }
    throw new BusinessException(ErrorStatus.PAYMENT_PROVIDER_NOT_SUPPORTED);
  }
}
