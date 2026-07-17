package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;

/**
 * PG사 결제 취소(환불) API 호출용 클라이언트.
 *
 * <p>실제 provider 구현체는 {@link #provider()}로 자신이 담당하는 PG를 알리고, {@link PaymentCancelClientRouter}가 이를
 * 키로 매핑해 요청 provider에 맞는 클라이언트를 선택해 호출한다. 모든 provider를 대신 처리하는 fallback 구현체는 {@link #isFallback()}을
 * {@code true}로 재정의한다.
 */
public interface PaymentCancelClient {

  /** 이 클라이언트가 담당하는 PG provider. 라우터가 Map 키로 사용한다. fallback 구현체에서는 호출되지 않는다. */
  PaymentProvider provider();

  /** 실제 provider 구현체가 없을 때 라우터가 선택하는 fallback 여부. */
  default boolean isFallback() {
    return false;
  }

  PaymentCancelResult cancel(PaymentCancelCommand command);
}
