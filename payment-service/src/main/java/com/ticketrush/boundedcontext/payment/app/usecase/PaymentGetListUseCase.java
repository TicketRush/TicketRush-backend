package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentSummaryResponse;
import com.ticketrush.boundedcontext.payment.app.mapper.PaymentMapper;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 사용자의 결제 내역(COMPLETED)을 오프셋 페이징으로 조회한다.
 *
 * <p>정렬은 이 UseCase가 고정 조립하며 클라이언트가 지정할 수 없다(#475). 이전에는 컨트롤러가 {@code Pageable}을 그대로 바인딩해 {@code
 * ?sort=} 로 임의 프로퍼티를 덮어쓸 수 있었고, 그 결과 ①존재하지 않는 프로퍼티가 {@code PropertyReferenceException} → 500으로 나가고
 * ②{@code paymentKey}(200자)·{@code approvalNumber} 같은 문자열 컬럼 정렬이 sort buffer 비용을 키우며 ③{@code
 * failureCode}·{@code pgFailureCode}처럼 응답 DTO에 없는 내부 값의 순서를 관측할 수 있었다({@code Sort}가 받는 것은 컬럼명이 아니라
 * 엔티티 프로퍼티명이다).
 *
 * <p>{@code paidAt} 단독 정렬은 동일 시각에 승인된 결제가 있으면 전순서가 확정되지 않아 오프셋 페이징의 페이지 경계에서 중복·누락이 생길 수 있다. PK인
 * {@code id}를 tie-break로 덧붙여 전순서를 확정한다.
 *
 * <p>정렬 키가 {@code idx_payment_user_id_status}에 포함되지 않아 filesort는 그대로 남는다. 커버링 인덱스는 이 작업 범위 밖이다
 * ({@code Payment} 클래스 javadoc 참고).
 */
@Service
@RequiredArgsConstructor
public class PaymentGetListUseCase {

  private static final Sort PAID_AT_DESC =
      Sort.by(Sort.Order.desc("paidAt"), Sort.Order.desc("id"));

  private final PaymentRepository paymentRepository;
  private final PaymentMapper paymentMapper;

  @Transactional(readOnly = true)
  public Page<PaymentSummaryResponse> execute(Long userId, OffsetPageRequest pageRequest) {
    Pageable pageable = PageRequest.of(pageRequest.page(), pageRequest.size(), PAID_AT_DESC);

    return paymentRepository
        .findByUserIdAndStatus(userId, PaymentStatus.COMPLETED, pageable)
        .map(paymentMapper::toSummaryResponse);
  }
}
