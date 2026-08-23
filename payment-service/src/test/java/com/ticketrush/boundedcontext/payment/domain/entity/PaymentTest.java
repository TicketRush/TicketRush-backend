package com.ticketrush.boundedcontext.payment.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentTest {

  private Payment payment(PaymentStatus status) {
    return Payment.builder()
        .bookingId(100L)
        .userId(10L)
        .seatId(200L)
        .provider(PaymentProvider.TOSS)
        .amount(55_000L)
        .status(status)
        .paymentKey("pgKey_xyz")
        .approvalNumber("approval-1")
        .method("카드")
        .paidAt(LocalDateTime.of(2026, 5, 22, 10, 0))
        .build();
  }

  @Test
  @DisplayName("결제수단은 PG 원본 문자열 그대로 저장한다")
  void method_is_stored_as_raw_pg_string() {
    assertThat(payment(PaymentStatus.COMPLETED).getMethod()).isEqualTo("카드");
  }

  @Test
  @DisplayName("결제수단이 컬럼 길이를 넘으면 앞에서부터 50자만 남기고 자른다")
  void method_is_truncated_when_too_long() {
    /* 이 컬럼은 성공 경로에서만 채워지므로, 길이 초과로 INSERT가 깨지면 PG 과금이 끝난 뒤 500이 나고 payment row는
     * 남지 않는다(PaymentFacade.confirm이 멱등 조회에 실패해 원 예외를 재던진다). Toss가 값을 늘려도 그 사고가
     * 나지 않도록 방어적으로 자른다(#593).
     *
     * 입력의 앞뒤를 서로 다른 마커로 구분해, 길이뿐 아니라 "앞에서부터" 자른다는 것까지 고정한다. */
    String head = "A".repeat(50);
    String tooLong = head + "Z".repeat(10);

    Payment payment =
        Payment.builder()
            .bookingId(100L)
            .provider(PaymentProvider.TOSS)
            .amount(55_000L)
            .status(PaymentStatus.COMPLETED)
            .method(tooLong)
            .build();

    assertThat(payment.getMethod()).isEqualTo(head);
  }

  @Test
  @DisplayName("승인번호가 컬럼 길이를 넘으면 앞에서부터 100자만 남기고 자른다")
  void approvalNumber_is_truncated_when_too_long() {
    /* 승인번호에는 transactionKey가 없을 때 paymentKey가 폴백으로 들어오는데(#89), paymentKey는 계약상 최대
     * 200자라(#413) 컬럼 폭 100을 넘을 수 있다. method와 마찬가지로 성공 경로에서만 채워지는 컬럼이라, 길이 초과로
     * INSERT가 깨지면 PG 과금이 끝난 뒤 500이 나고 payment row는 남지 않는다(#619).
     *
     * 입력을 계약 상한인 200자로 잡아 최악 케이스를 그대로 재현하고, 앞뒤를 다른 마커로 구분해 절단 방향까지 고정한다. */
    String head = "A".repeat(Payment.APPROVAL_NUMBER_MAX_LENGTH);
    String tooLong = head + "Z".repeat(100);

    Payment payment =
        Payment.builder()
            .bookingId(100L)
            .provider(PaymentProvider.TOSS)
            .amount(55_000L)
            .status(PaymentStatus.COMPLETED)
            .approvalNumber(tooLong)
            .build();

    assertThat(payment.getApprovalNumber()).isEqualTo(head);
  }

  @Test
  @DisplayName("승인번호가 컬럼 길이와 정확히 같으면 자르지 않는다")
  void approvalNumber_is_kept_when_exactly_at_column_length() {
    /* 상한과 같은 길이는 손대지 않는다는 계약을 명시한다. 탐지력은 제한적이다 — 조건이 <= 에서 < 로 바뀌어도
     * substring(0, maxLength)가 같은 값을 돌려주므로 이 테스트로는 드러나지 않는다(실측 확인). 상한을 관측하는
     * TossPaymentApprovalClient가 같은 경계를 쓰므로, 경계가 어느 쪽인지를 코드로 남겨두는 것이 이 테스트의 몫이다. */
    String exact = "A".repeat(Payment.APPROVAL_NUMBER_MAX_LENGTH);

    Payment payment =
        Payment.builder()
            .bookingId(100L)
            .provider(PaymentProvider.TOSS)
            .amount(55_000L)
            .status(PaymentStatus.COMPLETED)
            .approvalNumber(exact)
            .build();

    assertThat(payment.getApprovalNumber()).isEqualTo(exact);
  }

  @Test
  @DisplayName("COMPLETED 결제는 markCanceled로 CANCELED 상태로 전이한다")
  void markCanceled_transitions_from_completed() {
    // given
    Payment payment = payment(PaymentStatus.COMPLETED);

    // when
    payment.markCanceled();

    // then
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
  }

  @Test
  @DisplayName("이미 CANCELED 된 결제에 markCanceled를 호출하면 IllegalStateException을 던진다")
  void markCanceled_rejects_already_canceled() {
    // given
    Payment payment = payment(PaymentStatus.CANCELED);

    // expect
    assertThatThrownBy(payment::markCanceled).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("COMPLETED가 아닌 상태(PENDING)에서 markCanceled를 호출하면 IllegalStateException을 던진다")
  void markCanceled_rejects_non_completed() {
    // given
    Payment payment = payment(PaymentStatus.PENDING);

    // expect
    assertThatThrownBy(payment::markCanceled).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("COMPLETED 결제는 isAlreadyProcessed가 true다")
  void isAlreadyProcessed_true_when_completed() {
    // given
    Payment payment = payment(PaymentStatus.COMPLETED);

    // expect
    assertThat(payment.isAlreadyProcessed()).isTrue();
  }

  @Test
  @DisplayName("COMPLETED가 아닌 결제는 isAlreadyProcessed가 false다")
  void isAlreadyProcessed_false_when_not_completed() {
    // expect
    assertThat(payment(PaymentStatus.PENDING).isAlreadyProcessed()).isFalse();
    assertThat(payment(PaymentStatus.CANCELED).isAlreadyProcessed()).isFalse();
    assertThat(payment(PaymentStatus.FAILED).isAlreadyProcessed()).isFalse();
  }

  @Test
  @DisplayName("failed 팩토리는 FAILED 상태로 내부·PG 원본 코드/사유를 담고 paymentKey는 비운다")
  void failed_creates_failed_payment_with_meta() {
    // when
    Payment failed =
        Payment.failed(
            100L,
            10L,
            200L,
            PaymentProvider.TOSS,
            55_000L,
            "PAYMENT_400_003",
            "결제가 거절되었습니다.",
            "REJECT_CARD_COMPANY",
            "카드사에서 거절한 카드입니다");

    // then
    assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
    assertThat(failed.getFailureCode()).isEqualTo("PAYMENT_400_003");
    assertThat(failed.getFailureReason()).isEqualTo("결제가 거절되었습니다.");
    assertThat(failed.getPgFailureCode()).isEqualTo("REJECT_CARD_COMPANY");
    assertThat(failed.getPgFailureReason()).isEqualTo("카드사에서 거절한 카드입니다");
    assertThat(failed.getBookingId()).isEqualTo(100L);
    assertThat(failed.getUserId()).isEqualTo(10L);
    assertThat(failed.getSeatId()).isEqualTo(200L);
    assertThat(failed.getAmount()).isEqualTo(55_000L);
    // 결제가 성립하지 않았으므로 결제 성립 관련 필드는 비운다.
    assertThat(failed.getPaymentKey()).isNull();
    assertThat(failed.getApprovalNumber()).isNull();
    assertThat(failed.getPaidAt()).isNull();
    assertThat(failed.isAlreadyProcessed()).isFalse();
  }

  @Test
  @DisplayName("failed 팩토리는 PG 원본 코드/사유가 컬럼 길이를 넘으면 잘라 담는다(50/255)")
  void failed_truncates_pg_fields_over_column_length() {
    // given: pgFailureCode 컬럼 길이 50, pgFailureReason 255를 초과하는 원본
    String longCode = "C".repeat(80);
    String longReason = "가".repeat(300);

    // when
    Payment failed =
        Payment.failed(
            100L,
            10L,
            200L,
            PaymentProvider.TOSS,
            55_000L,
            "PAYMENT_400_003",
            "거절",
            longCode,
            longReason);

    // then
    assertThat(failed.getPgFailureCode()).hasSize(50).isEqualTo("C".repeat(50));
    assertThat(failed.getPgFailureReason()).hasSize(255).isEqualTo("가".repeat(255));
  }

  @Test
  @DisplayName("failed 팩토리는 PG 원본이 없으면(null) 그대로 null로 둔다")
  void failed_allows_null_pg_fields() {
    // when
    Payment failed =
        Payment.failed(
            100L, 10L, 200L, PaymentProvider.TOSS, 55_000L, "PAYMENT_400_003", "거절", null, null);

    // then
    assertThat(failed.getPgFailureCode()).isNull();
    assertThat(failed.getPgFailureReason()).isNull();
  }
}
