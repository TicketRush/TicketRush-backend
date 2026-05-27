package com.ticketrush.boundedcontext.payment.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentSummaryResponse;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class PaymentGetListUseCaseTest {

  @Mock private PaymentRepository paymentRepository;

  @InjectMocks private PaymentGetListUseCase paymentGetListUseCase;

  @Test
  @DisplayName("로그인 사용자의 COMPLETED 결제 내역을 페이지로 반환한다")
  void execute_returns_completed_payments() throws Exception {
    // given
    Long userId = 10L;
    Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "paidAt"));
    Payment payment = sample(1L, userId, 100L, 55_000L);
    Page<Payment> page = new PageImpl<>(List.of(payment), pageable, 1);
    given(paymentRepository.findByUserIdAndStatus(userId, PaymentStatus.COMPLETED, pageable))
        .willReturn(page);

    // when
    Page<PaymentSummaryResponse> result = paymentGetListUseCase.execute(userId, pageable);

    // then
    assertThat(result.getTotalElements()).isEqualTo(1);
    PaymentSummaryResponse first = result.getContent().get(0);
    assertThat(first.paymentId()).isEqualTo(1L);
    assertThat(first.bookingId()).isEqualTo(100L);
    assertThat(first.amount()).isEqualTo(55_000L);
    assertThat(first.status()).isEqualTo(PaymentStatus.COMPLETED);
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
