package com.ticketrush.boundedcontext.payment.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentSummaryResponse;
import com.ticketrush.boundedcontext.payment.app.mapper.PaymentMapper;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class PaymentGetListUseCaseTest {

  @Mock private PaymentRepository paymentRepository;

  @Spy private PaymentMapper paymentMapper = Mappers.getMapper(PaymentMapper.class);

  @InjectMocks private PaymentGetListUseCase paymentGetListUseCase;

  @Captor private ArgumentCaptor<Pageable> pageableCaptor;

  /*
   * 이 테스트는 매핑 결과만 본다. 어떤 Pageable로 조회하는지(page·size·정렬)는 아래
   * execute_assemblesFixedSort가 전담하므로 여기서는 any(Pageable.class)로 느슨하게 둔다.
   * 정렬 프로퍼티가 실제 엔티티로 해석되는지는 PaymentGetListUseCaseIntegrationTest가 맡는다.
   */
  @Test
  @DisplayName("로그인 사용자의 COMPLETED 결제 내역을 페이지로 반환한다")
  void execute_returns_completed_payments() throws Exception {
    // given
    Long userId = 10L;
    OffsetPageRequest pageRequest = new OffsetPageRequest(0, 10);
    Payment payment = sample(1L, userId, 100L, 55_000L);
    Page<Payment> page = new PageImpl<>(List.of(payment), PageRequest.of(0, 10), 1);
    given(
            paymentRepository.findByUserIdAndStatus(
                eq(userId), eq(PaymentStatus.COMPLETED), any(Pageable.class)))
        .willReturn(page);

    // when
    Page<PaymentSummaryResponse> result = paymentGetListUseCase.execute(userId, pageRequest);

    // then
    assertThat(result.getTotalElements()).isEqualTo(1);
    PaymentSummaryResponse first = result.getContent().get(0);
    assertThat(first.paymentId()).isEqualTo(1L);
    assertThat(first.bookingId()).isEqualTo(100L);
    assertThat(first.amount()).isEqualTo(55_000L);
    assertThat(first.status()).isEqualTo(PaymentStatus.COMPLETED);
  }

  @Test
  @DisplayName("정렬은 paidAt DESC + id DESC로 고정 조립된다 — 호출자가 지정할 수 없다(#475)")
  void execute_assemblesFixedSort() {
    // given
    Long userId = 10L;
    OffsetPageRequest pageRequest = new OffsetPageRequest(2, 25);
    given(
            paymentRepository.findByUserIdAndStatus(
                eq(userId), eq(PaymentStatus.COMPLETED), any(Pageable.class)))
        .willReturn(Page.empty());

    // when
    paymentGetListUseCase.execute(userId, pageRequest);

    // then
    then(paymentRepository)
        .should()
        .findByUserIdAndStatus(eq(userId), eq(PaymentStatus.COMPLETED), pageableCaptor.capture());

    Pageable captured = pageableCaptor.getValue();
    assertThat(captured.getPageNumber()).isEqualTo(2);
    assertThat(captured.getPageSize()).isEqualTo(25);
    assertThat(captured.getSort())
        .isEqualTo(Sort.by(Sort.Order.desc("paidAt"), Sort.Order.desc("id")));
  }

  private Payment sample(Long id, Long userId, Long bookingId, Long amount) throws Exception {
    Payment payment =
        Payment.builder()
            .bookingId(bookingId)
            .userId(userId)
            .provider(PaymentProvider.TOSS)
            .amount(amount)
            .status(PaymentStatus.COMPLETED)
            .paymentKey("pgKey_xyz")
            .approvalNumber("APR-001")
            .paidAt(LocalDateTime.of(2026, 5, 1, 10, 0))
            .build();
    Field idField = payment.getClass().getSuperclass().getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(payment, id);
    return payment;
  }
}
