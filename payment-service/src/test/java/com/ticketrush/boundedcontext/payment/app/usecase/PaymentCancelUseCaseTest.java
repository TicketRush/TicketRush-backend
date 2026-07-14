package com.ticketrush.boundedcontext.payment.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentCancelRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentCancelResponse;
import com.ticketrush.boundedcontext.payment.app.mapper.PaymentMapper;
import com.ticketrush.boundedcontext.payment.app.support.PaymentEventPublisher;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelClientRouter;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelCommand;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelResult;
import com.ticketrush.boundedcontext.payment.out.apiclient.TicketRestClient;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.boundedcontext.payment.out.repository.RefundRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class PaymentCancelUseCaseTest {

  @Mock private PaymentRepository paymentRepository;
  @Mock private RefundRepository refundRepository;
  @Mock private PaymentCancelClientRouter paymentCancelClientRouter;
  @Mock private PaymentCancelPersister paymentCancelPersister;
  @Mock private FailedRefundRecorder failedRefundRecorder;
  @Mock private PaymentEventPublisher paymentEventPublisher;
  @Mock private TicketRestClient ticketRestClient;

  @Spy private PaymentMapper paymentMapper = Mappers.getMapper(PaymentMapper.class);

  @InjectMocks private PaymentCancelUseCase paymentCancelUseCase;

  @Test
  @DisplayName("COMPLETED 결제 환불 성공 시 PG 취소 호출 + 영속화 위임 + 이벤트를 발행한다")
  void execute_success() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    Long bookingId = 100L;
    Long seatId = 200L;
    Long amount = 55_000L;
    final Long savedRefundId = 999L;
    LocalDateTime canceledAt = LocalDateTime.of(2026, 5, 22, 10, 0);
    final PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");

    Payment payment = completedPayment(paymentId, userId, bookingId, seatId, amount);
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));
    given(ticketRestClient.isTicketUsed(bookingId)).willReturn(false);
    given(paymentCancelClientRouter.cancel(any()))
        .willReturn(new PaymentCancelResult("PG-REFUND-1", amount, canceledAt));

    Payment canceled = completedPayment(paymentId, userId, bookingId, seatId, amount);
    canceled.markCanceled();
    given(paymentCancelPersister.persist(eq(paymentId), any(Refund.class)))
        .willAnswer(
            invocation -> {
              Refund r = invocation.getArgument(1);
              setId(r, savedRefundId);
              return new PaymentCancelPersister.CancelPersisted(canceled, r);
            });

    // when
    PaymentCancelResponse response = paymentCancelUseCase.execute(userId, paymentId, request);

    // then
    assertThat(response.paymentId()).isEqualTo(paymentId);
    assertThat(response.status()).isEqualTo("CANCELED");
    assertThat(response.refundId()).isEqualTo(savedRefundId);
    assertThat(response.refundedAmount()).isEqualTo(amount);
    assertThat(response.canceledAt()).isEqualTo(canceledAt);

    ArgumentCaptor<PaymentCancelCommand> commandCaptor =
        ArgumentCaptor.forClass(PaymentCancelCommand.class);
    verify(paymentCancelClientRouter).cancel(commandCaptor.capture());
    PaymentCancelCommand command = commandCaptor.getValue();
    assertThat(command.provider()).isEqualTo(PaymentProvider.TOSS);
    assertThat(command.paymentKey()).isEqualTo("pgKey_xyz");
    assertThat(command.amount()).isEqualTo(amount);
    assertThat(command.reason()).isEqualTo("단순 변심");
    assertThat(command.idempotencyKey()).isEqualTo("REFUND-0000001");

    ArgumentCaptor<Refund> refundCaptor = ArgumentCaptor.forClass(Refund.class);
    verify(paymentCancelPersister).persist(eq(paymentId), refundCaptor.capture());
    Refund builtRefund = refundCaptor.getValue();
    assertThat(builtRefund.getPaymentId()).isEqualTo(paymentId);
    assertThat(builtRefund.getBookingId()).isEqualTo(bookingId);
    assertThat(builtRefund.getPrice()).isEqualTo(amount);
    assertThat(builtRefund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
    assertThat(builtRefund.getPgRefundKey()).isEqualTo("PG-REFUND-1");
    assertThat(builtRefund.getReason()).isEqualTo("단순 변심");
    assertThat(builtRefund.getConfirmedAt()).isEqualTo(canceledAt);

    verify(paymentEventPublisher)
        .publishCanceled(
            eq(paymentId),
            eq(bookingId),
            isNull(),
            eq(seatId),
            eq(savedRefundId),
            eq(amount),
            eq("단순 변심"),
            eq(canceledAt));

    // 이벤트는 영속화(persist) 커밋 이후에 발행되어야 한다.
    InOrder inOrder = inOrder(paymentCancelPersister, paymentEventPublisher);
    inOrder.verify(paymentCancelPersister).persist(eq(paymentId), any(Refund.class));
    inOrder
        .verify(paymentEventPublisher)
        .publishCanceled(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("이미 CANCELED 된 결제는 PG 재호출 없이 기존 환불 내역을 멱등하게 반환한다")
  void execute_idempotent_when_already_canceled() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;

    Payment payment = completedPayment(paymentId, userId, 100L, 200L, 55_000L);
    payment.markCanceled();
    Refund existing =
        Refund.builder()
            .paymentId(paymentId)
            .bookingId(100L)
            .price(55_000L)
            .status(RefundStatus.COMPLETED)
            .confirmedAt(LocalDateTime.of(2026, 5, 22, 10, 0))
            .build();
    setId(existing, 999L);

    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));
    given(refundRepository.findByPaymentId(paymentId)).willReturn(Optional.of(existing));

    PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");

    // when
    PaymentCancelResponse response = paymentCancelUseCase.execute(userId, paymentId, request);

    // then
    assertThat(response.refundId()).isEqualTo(999L);
    assertThat(response.status()).isEqualTo("CANCELED");
    verify(paymentCancelClientRouter, never()).cancel(any());
    verify(paymentCancelPersister, never()).persist(any(), any(Refund.class));
    verify(paymentEventPublisher, never())
        .publishCanceled(any(), any(), any(), any(), any(), any(), any(), any());
    // 이미 취소된 건은 가드 앞에서 조기 반환되므로 입장권 조회를 하지 않는다 (#416).
    verify(ticketRestClient, never()).isTicketUsed(any());
  }

  @Test
  @DisplayName("입장(ticket=USED)한 예매는 PG 취소 없이 PAYMENT_409_003 으로 거절한다 (#416)")
  void execute_fail_when_ticket_used() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    Long bookingId = 100L;
    PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");

    Payment payment = completedPayment(paymentId, userId, bookingId, 200L, 55_000L);
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));
    given(ticketRestClient.isTicketUsed(bookingId)).willReturn(true);

    // when & then
    assertThatThrownBy(() -> paymentCancelUseCase.execute(userId, paymentId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_CANCEL_NOT_ALLOWED_TICKET_USED);

    // 착석한 좌석이 환불 이벤트로 반환되지 않도록 PG 취소·영속화·이벤트 발행을 모두 하지 않는다.
    verify(paymentCancelClientRouter, never()).cancel(any());
    verify(paymentCancelPersister, never()).persist(any(), any(Refund.class));
    verify(paymentEventPublisher, never())
        .publishCanceled(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("입장권 조회가 통신 실패(503)면 PG 취소 이전에 예외가 전파되고 PG를 호출하지 않는다 (#416)")
  void execute_fail_when_ticket_lookup_communication_fails() throws Exception {
    // given: fail-closed — 판정할 수 없으면 PG 취소로 넘어가지 않고 취소를 거부해야 한다.
    Long userId = 10L;
    Long paymentId = 1L;
    Long bookingId = 100L;
    PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");

    Payment payment = completedPayment(paymentId, userId, bookingId, 200L, 55_000L);
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));
    given(ticketRestClient.isTicketUsed(bookingId))
        .willThrow(new BusinessException(ErrorStatus.PAYMENT_TICKET_COMMUNICATION_FAILED));

    // when & then
    assertThatThrownBy(() -> paymentCancelUseCase.execute(userId, paymentId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_TICKET_COMMUNICATION_FAILED);

    verify(paymentCancelClientRouter, never()).cancel(any());
    verify(paymentCancelPersister, never()).persist(any(), any(Refund.class));
    verify(paymentEventPublisher, never())
        .publishCanceled(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("CANCELED 결제인데 환불 내역이 없으면 정합성 오류(PAYMENT_500_001)를 던진다")
  void execute_fail_when_canceled_but_refund_missing() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;

    Payment payment = completedPayment(paymentId, userId, 100L, 200L, 55_000L);
    payment.markCanceled();
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));
    given(refundRepository.findByPaymentId(paymentId)).willReturn(Optional.empty());

    PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");

    // when & then
    assertThatThrownBy(() -> paymentCancelUseCase.execute(userId, paymentId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_REFUND_INCONSISTENT);

    verify(paymentCancelClientRouter, never()).cancel(any());
    verify(paymentCancelPersister, never()).persist(any(), any(Refund.class));
  }

  @Test
  @DisplayName("본인 결제가 아니거나 존재하지 않으면 PAYMENT_404_002 예외가 발생한다")
  void execute_fail_when_not_found() {
    // given
    Long userId = 10L;
    Long paymentId = 999L;
    PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> paymentCancelUseCase.execute(userId, paymentId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_NOT_FOUND);

    verify(paymentCancelClientRouter, never()).cancel(any());
    verify(paymentCancelPersister, never()).persist(any(), any(Refund.class));
  }

  @Test
  @DisplayName("COMPLETED/CANCELED 가 아닌 결제는 PAYMENT_409_002 예외가 발생한다")
  void execute_fail_when_not_cancelable() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");

    Payment payment =
        Payment.builder()
            .bookingId(100L)
            .userId(userId)
            .seatId(200L)
            .provider(PaymentProvider.TOSS)
            .amount(55_000L)
            .status(PaymentStatus.PENDING)
            .paymentKey("pgKey_xyz")
            .build();
    setId(payment, paymentId);
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));

    // when & then
    assertThatThrownBy(() -> paymentCancelUseCase.execute(userId, paymentId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_NOT_CANCELABLE);

    verify(paymentCancelClientRouter, never()).cancel(any());
    verify(paymentCancelPersister, never()).persist(any(), any(Refund.class));
  }

  @Test
  @DisplayName("PG 환불이 실패하면 예외가 전파되고 Refund 저장/이벤트 발행을 하지 않는다")
  void execute_fail_when_pg_refund_fails() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");

    Payment payment = completedPayment(paymentId, userId, 100L, 200L, 55_000L);
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));
    given(ticketRestClient.isTicketUsed(anyLong())).willReturn(false);
    given(paymentCancelClientRouter.cancel(any()))
        .willThrow(new BusinessException(ErrorStatus.PAYMENT_REFUND_FAILED));

    // when & then
    assertThatThrownBy(() -> paymentCancelUseCase.execute(userId, paymentId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_REFUND_FAILED);

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    verify(paymentCancelPersister, never()).persist(any(), any(Refund.class));
    verify(paymentEventPublisher, never())
        .publishCanceled(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("동시 취소로 paymentId unique 제약이 위반되면 예외가 전파되고 이벤트를 발행하지 않는다")
  void execute_propagates_constraint_violation_without_publishing() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");

    Payment payment = completedPayment(paymentId, userId, 100L, 200L, 55_000L);
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));
    given(ticketRestClient.isTicketUsed(anyLong())).willReturn(false);
    given(paymentCancelClientRouter.cancel(any()))
        .willReturn(new PaymentCancelResult("PG-REFUND-1", 55_000L, LocalDateTime.now()));
    given(paymentCancelPersister.persist(eq(paymentId), any(Refund.class)))
        .willThrow(new DataIntegrityViolationException("duplicate paymentId"));

    // when & then
    assertThatThrownBy(() -> paymentCancelUseCase.execute(userId, paymentId, request))
        .isInstanceOf(DataIntegrityViolationException.class);

    verify(paymentEventPublisher, never())
        .publishCanceled(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("getCanceledResponse 는 기존 환불 내역을 멱등하게 반환한다")
  void getCanceledResponse_returns_existing_refund() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;

    Payment payment = completedPayment(paymentId, userId, 100L, 200L, 55_000L);
    payment.markCanceled();
    Refund existing =
        Refund.builder()
            .paymentId(paymentId)
            .bookingId(100L)
            .price(55_000L)
            .status(RefundStatus.COMPLETED)
            .confirmedAt(LocalDateTime.of(2026, 5, 22, 10, 0))
            .build();
    setId(existing, 999L);

    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));
    given(refundRepository.findByPaymentId(paymentId)).willReturn(Optional.of(existing));

    // when
    PaymentCancelResponse response = paymentCancelUseCase.getCanceledResponse(userId, paymentId);

    // then
    assertThat(response.refundId()).isEqualTo(999L);
    assertThat(response.status()).isEqualTo("CANCELED");
    verify(paymentCancelClientRouter, never()).cancel(any());
    verify(paymentCancelPersister, never()).persist(any(), any(Refund.class));
  }

  @Test
  @DisplayName("PG 취소가 실패하면 FailedRefundRecorder에 위임하고 원 예외를 사용자에게 그대로 던진다(영속화·발행 없음)")
  void execute_delegates_to_recorder_and_rethrows_on_pg_failure() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    final PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");
    Payment payment = completedPayment(paymentId, userId, 100L, 200L, 55_000L);
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));
    given(ticketRestClient.isTicketUsed(anyLong())).willReturn(false);
    BusinessException rejected = new BusinessException(ErrorStatus.PAYMENT_REFUND_FAILED);
    given(paymentCancelClientRouter.cancel(any())).willThrow(rejected);

    // when & then: 저장은 부수효과일 뿐 사용자 응답은 예외 그대로 유지된다.
    assertThatThrownBy(() -> paymentCancelUseCase.execute(userId, paymentId, request))
        .isSameAs(rejected);

    verify(failedRefundRecorder).recordIfRejected(payment, rejected);
    verify(paymentCancelPersister, never()).persist(any(), any(Refund.class));
    verify(paymentEventPublisher, never())
        .publishCanceled(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("getCanceledResponse가 COMPLETED가 아닌 환불(FAILED)만 있으면 REFUND_INCONSISTENT로 표면화한다")
  void getCanceledResponse_rejects_non_completed_refund() throws Exception {
    // given: FAILED가 unique 슬롯을 점유한 상태의 충돌(#334)을 멱등 성공으로 오분류하지 않도록 표면화해야 한다.
    Long userId = 10L;
    Long paymentId = 1L;
    Payment payment = completedPayment(paymentId, userId, 100L, 200L, 55_000L);
    Refund failed =
        Refund.failed(
            paymentId, 100L, 55_000L, "PG사 환불 처리에 실패했습니다.", LocalDateTime.of(2026, 5, 22, 10, 0));
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));
    given(refundRepository.findByPaymentId(paymentId)).willReturn(Optional.of(failed));

    // when & then
    assertThatThrownBy(() -> paymentCancelUseCase.getCanceledResponse(userId, paymentId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_REFUND_INCONSISTENT);
  }

  private Payment completedPayment(
      Long paymentId, Long userId, Long bookingId, Long seatId, Long amount) throws Exception {
    Payment payment =
        Payment.builder()
            .bookingId(bookingId)
            .userId(userId)
            .seatId(seatId)
            .provider(PaymentProvider.TOSS)
            .amount(amount)
            .status(PaymentStatus.COMPLETED)
            .paymentKey("pgKey_xyz")
            .approvalNumber("APR-1")
            .paidAt(LocalDateTime.of(2026, 5, 1, 10, 0))
            .build();
    setId(payment, paymentId);
    return payment;
  }

  private void setId(Object entity, Long id) throws Exception {
    Field idField = entity.getClass().getSuperclass().getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(entity, id);
  }
}
