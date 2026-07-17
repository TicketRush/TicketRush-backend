package com.ticketrush.boundedcontext.payment.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.app.support.PaymentEventPublisher;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentRefundByBookingUseCase.RefundOutcome;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelClientRouter;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelCommand;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelResult;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.boundedcontext.payment.out.repository.RefundRepository;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.booking.event.RefundRequestedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentRefundByBookingUseCaseTest {

  @Mock private PaymentRepository paymentRepository;
  @Mock private RefundRepository refundRepository;
  @Mock private PaymentCancelClientRouter paymentCancelClientRouter;
  @Mock private PaymentCancelPersister paymentCancelPersister;
  @Mock private FailedRefundRecorder failedRefundRecorder;
  @Mock private PaymentEventPublisher paymentEventPublisher;

  private SimpleMeterRegistry meterRegistry;
  private PaymentRefundByBookingUseCase paymentRefundByBookingUseCase;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    paymentRefundByBookingUseCase =
        new PaymentRefundByBookingUseCase(
            paymentRepository,
            refundRepository,
            paymentCancelClientRouter,
            paymentCancelPersister,
            failedRefundRecorder,
            paymentEventPublisher,
            meterRegistry);
  }

  private static final Long PAYMENT_ID = 1L;
  private static final Long BOOKING_ID = 100L;
  private static final Long SEAT_ID = 200L;
  private static final Long AMOUNT = 55_000L;
  private static final String BOOKING_NUMBER = "BOOK-1234";

  private RefundRequestedEvent event() {
    return new RefundRequestedEvent(
        BOOKING_ID, BOOKING_NUMBER, SEAT_ID, 10L, LocalDateTime.of(2026, 5, 22, 10, 0));
  }

  @Test
  @DisplayName(
      "COMPLETED 결제가 있으면 PG 취소 후 bookingNumber를 실어 PaymentCanceledEvent를 발행하고 REFUNDED를 반환한다")
  void execute_refunds_and_publishes_with_booking_number() throws Exception {
    // given
    final Long savedRefundId = 999L;
    LocalDateTime canceledAt = LocalDateTime.of(2026, 5, 22, 10, 30);

    Payment payment = completedPayment();
    given(paymentRepository.findFirstByBookingIdAndStatus(BOOKING_ID, PaymentStatus.COMPLETED))
        .willReturn(Optional.of(payment));
    given(paymentCancelClientRouter.cancel(any()))
        .willReturn(new PaymentCancelResult("PG-REFUND-1", AMOUNT, canceledAt));

    Payment canceled = completedPayment();
    canceled.markCanceled();
    given(paymentCancelPersister.persist(eq(PAYMENT_ID), any(Refund.class)))
        .willAnswer(
            invocation -> {
              Refund r = invocation.getArgument(1);
              setId(r, savedRefundId);
              return new PaymentCancelPersister.CancelPersisted(canceled, r);
            });

    // when
    RefundOutcome outcome = paymentRefundByBookingUseCase.execute(event());

    // then
    assertThat(outcome).isEqualTo(RefundOutcome.REFUNDED);

    ArgumentCaptor<PaymentCancelCommand> commandCaptor =
        ArgumentCaptor.forClass(PaymentCancelCommand.class);
    verify(paymentCancelClientRouter).cancel(commandCaptor.capture());
    PaymentCancelCommand command = commandCaptor.getValue();
    assertThat(command.provider()).isEqualTo(PaymentProvider.TOSS);
    assertThat(command.paymentKey()).isEqualTo("pgKey_xyz");
    assertThat(command.amount()).isEqualTo(AMOUNT);
    assertThat(command.idempotencyKey()).isEqualTo("REFUND-0000001");

    // 성공 이벤트에 bookingNumber가 실려야 좌석 소유 교차검증(ABA 방지)이 가능하다.
    verify(paymentEventPublisher)
        .publishCanceled(
            eq(PAYMENT_ID),
            eq(BOOKING_ID),
            eq(BOOKING_NUMBER),
            eq(SEAT_ID),
            eq(savedRefundId),
            eq(AMOUNT),
            eq("사용자 예매 취소"),
            eq(canceledAt));

    // 이벤트는 영속화(persist) 커밋 이후에 발행되어야 한다.
    InOrder inOrder = inOrder(paymentCancelPersister, paymentEventPublisher);
    inOrder.verify(paymentCancelPersister).persist(eq(PAYMENT_ID), any(Refund.class));
    inOrder
        .verify(paymentEventPublisher)
        .publishCanceled(any(), any(), any(), any(), any(), any(), any(), any());

    // PG 취소 Latency 타이머가 provider 태그와 함께 기록되어야 한다.
    assertThat(
            meterRegistry
                .timer(MetricNames.PAYMENT_PG_CANCEL, MetricNames.TAG_PROVIDER, "TOSS")
                .count())
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("결제 자체가 없으면(COMPLETED·CANCELED 모두 없음) 멱등 스킵하고 ALREADY_SETTLED를 반환한다")
  void execute_is_idempotent_when_no_payment() {
    // given
    given(paymentRepository.findFirstByBookingIdAndStatus(BOOKING_ID, PaymentStatus.COMPLETED))
        .willReturn(Optional.empty());
    given(paymentRepository.findFirstByBookingIdAndStatus(BOOKING_ID, PaymentStatus.CANCELED))
        .willReturn(Optional.empty());

    // when
    RefundOutcome outcome = paymentRefundByBookingUseCase.execute(event());

    // then
    assertThat(outcome).isEqualTo(RefundOutcome.ALREADY_SETTLED);
    verify(paymentCancelClientRouter, never()).cancel(any());
    verify(paymentCancelPersister, never()).persist(any(), any(Refund.class));
    verify(paymentEventPublisher, never())
        .publishCanceled(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "이미 환불(CANCELED)된 건이 재전달되면 커밋된 환불 데이터로 PaymentCanceledEvent를 재발행하고 REPUBLISHED를 반환한다")
  void execute_republishes_when_already_refunded() throws Exception {
    // given: 최초 처리에서 환불은 커밋됐으나 발행이 유실돼 booking이 REFUNDING·좌석이 SOLD로 남은 상황
    final Long savedRefundId = 999L;
    LocalDateTime refundConfirmedAt = LocalDateTime.of(2026, 5, 22, 10, 30);

    Payment canceled = completedPayment();
    canceled.markCanceled();
    Refund refund =
        Refund.builder()
            .paymentId(PAYMENT_ID)
            .bookingId(BOOKING_ID)
            .price(AMOUNT)
            .status(RefundStatus.COMPLETED)
            .reason("사용자 예매 취소")
            .confirmedAt(refundConfirmedAt)
            .build();
    setId(refund, savedRefundId);

    given(paymentRepository.findFirstByBookingIdAndStatus(BOOKING_ID, PaymentStatus.COMPLETED))
        .willReturn(Optional.empty());
    given(paymentRepository.findFirstByBookingIdAndStatus(BOOKING_ID, PaymentStatus.CANCELED))
        .willReturn(Optional.of(canceled));
    given(refundRepository.findByBookingId(BOOKING_ID)).willReturn(Optional.of(refund));

    // when
    RefundOutcome outcome = paymentRefundByBookingUseCase.execute(event());

    // then: PG 재호출·재영속화 없이 커밋된 데이터만 재전파한다.
    assertThat(outcome).isEqualTo(RefundOutcome.REPUBLISHED);
    verify(paymentCancelClientRouter, never()).cancel(any());
    verify(paymentCancelPersister, never()).persist(any(), any(Refund.class));
    verify(paymentEventPublisher)
        .publishCanceled(
            eq(PAYMENT_ID),
            eq(BOOKING_ID),
            eq(BOOKING_NUMBER),
            eq(SEAT_ID),
            eq(savedRefundId),
            eq(AMOUNT),
            eq("사용자 예매 취소"),
            eq(refundConfirmedAt));
  }

  @Test
  @DisplayName("PG 취소가 실패하면 FailedRefundRecorder에 위임하고 원 예외를 재던진다(영속화·발행 없음)")
  void execute_delegates_to_recorder_and_rethrows_on_pg_failure() throws Exception {
    // given
    Payment payment = completedPayment();
    given(paymentRepository.findFirstByBookingIdAndStatus(BOOKING_ID, PaymentStatus.COMPLETED))
        .willReturn(Optional.of(payment));
    BusinessException rejected = new BusinessException(ErrorStatus.PAYMENT_REFUND_FAILED);
    given(paymentCancelClientRouter.cancel(any())).willThrow(rejected);

    // when & then: 원 예외가 그대로 리스너로 전파돼 보상/재시도로 분류된다.
    assertThatThrownBy(() -> paymentRefundByBookingUseCase.execute(event())).isSameAs(rejected);

    // FAILED 이력 기록은 recorder에 위임한다(분류·저장·멱등은 FailedRefundRecorderTest에서 검증).
    verify(failedRefundRecorder).recordIfRejected(payment, rejected);
    verify(paymentCancelPersister, never()).persist(any(), any(Refund.class));
    verify(paymentEventPublisher, never())
        .publishCanceled(any(), any(), any(), any(), any(), any(), any(), any());
  }

  private Payment completedPayment() throws Exception {
    Payment payment =
        Payment.builder()
            .bookingId(BOOKING_ID)
            .userId(10L)
            .seatId(SEAT_ID)
            .provider(PaymentProvider.TOSS)
            .amount(AMOUNT)
            .status(PaymentStatus.COMPLETED)
            .paymentKey("pgKey_xyz")
            .approvalNumber("APR-1")
            .paidAt(LocalDateTime.of(2026, 5, 1, 10, 0))
            .build();
    setId(payment, PAYMENT_ID);
    return payment;
  }

  private void setId(Object entity, Long id) throws Exception {
    Field idField = entity.getClass().getSuperclass().getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(entity, id);
  }
}
