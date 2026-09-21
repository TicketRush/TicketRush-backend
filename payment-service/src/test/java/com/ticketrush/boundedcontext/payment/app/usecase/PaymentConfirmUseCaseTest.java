package com.ticketrush.boundedcontext.payment.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentConfirmRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentConfirmResponse;
import com.ticketrush.boundedcontext.payment.app.mapper.PaymentMapper;
import com.ticketrush.boundedcontext.payment.app.support.PaymentEventPublisher;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.out.apiclient.BookingRestClient;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalClientRouter;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalRequest;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalResponse;
import com.ticketrush.boundedcontext.payment.out.apiclient.PgRejectionException;
import com.ticketrush.boundedcontext.payment.out.apiclient.dto.BookingInfoResponse;
import com.ticketrush.boundedcontext.payment.out.repository.ExpiredBookingRepository;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentConfirmUseCaseTest {

  /** 픽스처가 세우는 예매의 소유자. 모든 기존 케이스가 이 값으로 결제를 요청한다. */
  private static final Long DEFAULT_OWNER_ID = 10L;

  /**
   * 픽스처가 세우는 예매의 예매번호. 그대로 PG 승인 요청의 {@code orderId}가 된다(#662).
   *
   * <p>{@code BookingNumberGenerator}가 실제로 만드는 형식({@code XXXXX-XXXXX} 11자, 문자셋 {@code
   * 23456789ABCDEFGHJKMNPQRSTUVWXYZ})을 그대로 쓴다. 임의 문자열을 쓰면 하이픈·길이·문자셋이 가공 없이 전달되는지를 이 픽스처로는 확인할 수
   * 없다.
   */
  private static final String DEFAULT_BOOKING_NUMBER = "RQGEF-Q2FCW";

  @Mock private PaymentRepository paymentRepository;
  @Mock private PaymentApprovalClientRouter paymentApprovalClientRouter;
  @Mock private PaymentEventPublisher paymentEventPublisher;
  @Mock private ExpiredBookingRepository expiredBookingRepository;
  @Mock private BookingRestClient bookingRestClient;

  @Spy private PaymentMapper paymentMapper = Mappers.getMapper(PaymentMapper.class);

  private SimpleMeterRegistry meterRegistry;
  private PaymentConfirmUseCase paymentConfirmUseCase;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    paymentConfirmUseCase =
        new PaymentConfirmUseCase(
            paymentRepository,
            paymentApprovalClientRouter,
            paymentEventPublisher,
            expiredBookingRepository,
            bookingRestClient,
            paymentMapper,
            meterRegistry);
  }

  /**
   * PG 호출 앞의 선행 가드 둘(COMPLETED 중복·예매 상태 동기 확인)을 지정한 상태로 세운다.
   *
   * <p>{@code expired_booking} fast-path는 mock 기본값이 false라 따로 스텁하지 않는다 — 이벤트가 아직 도착하지 않아 fast-path가
   * 통과하는 상황이 이 가드(#490)가 실제로 겨냥한 창이다.
   */
  private void givenPreConfirmGuards(Long bookingId, String bookingStatus) {
    givenPreConfirmGuards(bookingId, DEFAULT_OWNER_ID, bookingStatus);
  }

  /**
   * 예매 소유자까지 지정해야 하는 케이스(#572)를 위한 오버로드.
   *
   * <p>기본 오버로드는 소유자를 {@link #DEFAULT_OWNER_ID}로 고정하고 모든 기존 케이스가 그 값으로 요청하므로, 소유자 가드는 기존 케이스의 판정에
   * 영향을 주지 않는다.
   */
  private void givenPreConfirmGuards(Long bookingId, Long ownerUserId, String bookingStatus) {
    givenPreConfirmGuards(bookingId, ownerUserId, bookingStatus, DEFAULT_BOOKING_NUMBER);
  }

  /**
   * 예매번호까지 지정해야 하는 케이스(#662)를 위한 오버로드.
   *
   * <p><b>#662 이전에는 이 값을 일부러 null로 고정했다.</b> 당시 {@code bookingNumber}는 환불 경로 전용이라 confirm이 그 필드를 보지
   * 않는다는 사실 자체가 무회귀의 증거였다. 이제는 이 값이 PG 승인 요청의 {@code orderId}가 되므로 전제가 뒤집혔다 — 기본 오버로드가 {@link
   * #DEFAULT_BOOKING_NUMBER}를 채우고, 값이 비었을 때 승인이 막히는지는 이 오버로드로 따로 검증한다.
   */
  private void givenPreConfirmGuards(
      Long bookingId, Long ownerUserId, String bookingStatus, String bookingNumber) {
    given(paymentRepository.existsByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED))
        .willReturn(false);
    given(bookingRestClient.getBooking(bookingId))
        .willReturn(
            new BookingInfoResponse(bookingId, ownerUserId, bookingStatus, bookingNumber, null));
  }

  private double guardBlockedCount(String reason) {
    return meterRegistry
        .counter(MetricNames.PAYMENT_CONFIRM_BOOKING_GUARD_BLOCKED, MetricNames.TAG_REASON, reason)
        .count();
  }

  @Test
  @DisplayName("PG가 결제수단을 내려주지 않아도 결제 확정은 성공하고 method만 null로 저장된다")
  void execute_succeeds_when_approval_has_no_method() throws Exception {
    /* 완료조건 3을 confirm 경로 끝까지 고정한다. 클라이언트 경계(TossPaymentApprovalClientTest)만으로는
     * UseCase나 엔티티 쪽에 누군가 null 가드를 새로 넣는 회귀를 잡지 못한다(#593). */
    final Long userId = 10L;
    Long bookingId = 100L;
    Long amount = 55_000L;
    final PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, amount, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, "PENDING");
    given(paymentApprovalClientRouter.approve(any()))
        .willReturn(
            new PaymentApprovalResponse(
                "APR-123", amount, LocalDateTime.of(2025, 1, 15, 10, 0), null));
    given(paymentRepository.saveAndFlush(any(Payment.class)))
        .willAnswer(
            invocation -> {
              Payment p = invocation.getArgument(0);
              setId(p, 999L);
              return p;
            });

    PaymentConfirmResponse response = paymentConfirmUseCase.execute(userId, request);

    assertThat(response.status()).isEqualTo("COMPLETED");

    ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
    verify(paymentRepository).saveAndFlush(paymentCaptor.capture());
    assertThat(paymentCaptor.getValue().getMethod()).isNull();
    assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
  }

  @Test
  @DisplayName("승인번호가 컬럼 길이를 넘어도 결제 확정은 실패하지 않고 잘려서 저장된다")
  void execute_succeeds_when_approval_number_exceeds_column_length() throws Exception {
    /* 완료조건 2를 confirm 경로 끝까지 고정한다(#619). transactionKey가 없으면 승인번호로 paymentKey(계약상 200자,
     * #413)가 폴백되는데, 잘리지 않으면 saveAndFlush가 DataIntegrityViolationException으로 깨지고 PaymentFacade는
     * 멱등 조회에도 실패해 원 예외를 재던진다 — PG 과금이 끝난 뒤 500이 나고 payment row는 남지 않는다.
     *
     * 다만 paymentRepository가 mock이라 이 테스트가 증명하는 것은 "저장에 넘기는 값이 컬럼 폭 이하"까지다. 실제 INSERT가
     * 깨지지 않는다는 것은 거기서 따라온다. 함께 단언하는 paymentKey 온전성은 자른 값이 정보를 잃지 않는 근거다 — 폴백
     * 값은 같은 row의 paymentKey와 동일한 값이라 원본이 옆 컬럼에 200자로 남는다. */
    final Long userId = 10L;
    Long bookingId = 100L;
    Long amount = 55_000L;
    String head = "H".repeat(Payment.APPROVAL_NUMBER_MAX_LENGTH);
    String longPaymentKey = head + "T".repeat(100);
    final PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, amount, longPaymentKey);

    givenPreConfirmGuards(bookingId, "PENDING");
    given(paymentApprovalClientRouter.approve(any()))
        .willReturn(
            new PaymentApprovalResponse(
                longPaymentKey, amount, LocalDateTime.of(2025, 1, 15, 10, 0), "카드"));
    given(paymentRepository.saveAndFlush(any(Payment.class)))
        .willAnswer(
            invocation -> {
              Payment p = invocation.getArgument(0);
              setId(p, 999L);
              return p;
            });

    PaymentConfirmResponse response = paymentConfirmUseCase.execute(userId, request);

    assertThat(response.status()).isEqualTo("COMPLETED");

    ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
    verify(paymentRepository).saveAndFlush(paymentCaptor.capture());
    assertThat(paymentCaptor.getValue().getApprovalNumber()).isEqualTo(head);
    assertThat(paymentCaptor.getValue().getPaymentKey()).isEqualTo(longPaymentKey);
  }

  @Test
  @DisplayName("PG 승인 성공 시 Payment를 COMPLETED 상태로 저장하고 PaymentConfirmedEvent를 발행한다")
  void execute_success() throws Exception {
    // given
    final Long userId = 10L;
    Long bookingId = 100L;
    Long seatId = 200L;
    Long amount = 55_000L;
    String paymentKey = "pgKey_xyz";
    String approvalNumber = "APR-123";
    Long savedPaymentId = 999L;
    LocalDateTime approvedAt = LocalDateTime.of(2025, 1, 15, 10, 0);
    final PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, seatId, PaymentProvider.TOSS, amount, paymentKey);

    givenPreConfirmGuards(bookingId, "PENDING");
    given(paymentApprovalClientRouter.approve(any()))
        .willReturn(new PaymentApprovalResponse(approvalNumber, amount, approvedAt, "카드"));
    given(paymentRepository.saveAndFlush(any(Payment.class)))
        .willAnswer(
            invocation -> {
              Payment p = invocation.getArgument(0);
              setId(p, savedPaymentId);
              return p;
            });

    // when
    PaymentConfirmResponse response = paymentConfirmUseCase.execute(userId, request);

    // then
    assertThat(response.paymentId()).isEqualTo(savedPaymentId);
    assertThat(response.status()).isEqualTo("COMPLETED");
    assertThat(response.paidAt()).isEqualTo(approvedAt);

    ArgumentCaptor<PaymentApprovalRequest> approvalCaptor =
        ArgumentCaptor.forClass(PaymentApprovalRequest.class);
    verify(paymentApprovalClientRouter).approve(approvalCaptor.capture());
    PaymentApprovalRequest sentApproval = approvalCaptor.getValue();
    assertThat(sentApproval.provider()).isEqualTo(PaymentProvider.TOSS);
    assertThat(sentApproval.paymentKey()).isEqualTo(paymentKey);
    // PG 주문번호는 booking이 발급한 예매번호 그대로다(#662). payment가 자체 규칙으로 만들면
    // 프론트가 결제창에 넣은 주문번호와 어긋나 PG가 승인을 거절한다.
    // 값을 DEFAULT_BOOKING_NUMBER 대신 리터럴로 적는다 — 픽스처와 단언이 같은 상수를 보면 상수 하나를
    // 잘못 고쳐도 둘이 함께 움직여 통과해 버린다.
    assertThat(sentApproval.orderId()).isEqualTo("RQGEF-Q2FCW");
    assertThat(sentApproval.bookingId()).isEqualTo(bookingId);
    assertThat(sentApproval.amount()).isEqualTo(amount);

    ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
    verify(paymentRepository).saveAndFlush(paymentCaptor.capture());
    Payment savedPayment = paymentCaptor.getValue();
    assertThat(savedPayment.getBookingId()).isEqualTo(bookingId);
    assertThat(savedPayment.getUserId()).isEqualTo(userId);
    assertThat(savedPayment.getProvider()).isEqualTo(PaymentProvider.TOSS);
    assertThat(savedPayment.getAmount()).isEqualTo(amount);
    assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(savedPayment.getPaymentKey()).isEqualTo(paymentKey);
    assertThat(savedPayment.getApprovalNumber()).isEqualTo(approvalNumber);
    /* 빌더 호출(.method)이나 @Builder 생성자 대입이 빠져도 컴파일은 통과하므로, 이 단언이 결제수단 저장의 유일한
     * 자동 방어선이다(#593). */
    assertThat(savedPayment.getMethod()).isEqualTo("카드");
    assertThat(savedPayment.getPaidAt()).isEqualTo(approvedAt);

    verify(paymentEventPublisher)
        .publishConfirmed(
            eq(savedPaymentId), eq(bookingId), eq(seatId), eq(userId), eq(amount), eq(approvedAt));

    // 이벤트는 saveAndFlush(커밋) 성공 이후에 발행되어야 한다.
    InOrder inOrder = inOrder(paymentRepository, paymentEventPublisher);
    inOrder.verify(paymentRepository).saveAndFlush(any(Payment.class));
    inOrder
        .verify(paymentEventPublisher)
        .publishConfirmed(any(), any(), any(), any(), any(), any());

    // 성공 시 result=success 카운터가 증가하고 PG 승인 Latency 타이머가 기록되어야 한다.
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.PAYMENT_CONFIRM, MetricNames.TAG_RESULT, MetricNames.RESULT_SUCCESS)
                .count())
        .isEqualTo(1.0);
    assertThat(
            meterRegistry
                .timer(MetricNames.PAYMENT_PG_APPROVE, MetricNames.TAG_PROVIDER, "TOSS")
                .count())
        .isEqualTo(1L);
  }

  private void setId(Payment payment, Long id) throws Exception {
    Field idField = payment.getClass().getSuperclass().getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(payment, id);
  }

  @Test
  @DisplayName("금액 불일치는 PG 승인 후(과금됨) 검증 실패라 FAILED로 기록하지 않는다")
  void execute_does_not_record_when_amount_mismatch() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    Long seatId = 200L;
    Long requestAmount = 55_000L;
    Long approvedAmount = 50_000L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(
            bookingId, seatId, PaymentProvider.KAKAO, requestAmount, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, "PENDING");
    given(paymentApprovalClientRouter.approve(any()))
        .willReturn(
            new PaymentApprovalResponse("APR-123", approvedAmount, LocalDateTime.now(), "카드"));

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_AMOUNT_MISMATCH);

    verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    verify(paymentEventPublisher, never())
        .publishConfirmed(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("PG가 이미 승인·과금 완료한 건(ALREADY_COMPLETED)은 성공 건이라 FAILED로 기록하지 않는다")
  void execute_does_not_record_when_pg_already_completed() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, "PENDING");
    given(paymentApprovalClientRouter.approve(any()))
        .willThrow(new BusinessException(ErrorStatus.PAYMENT_ALREADY_COMPLETED));

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_ALREADY_COMPLETED);

    verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    verify(paymentEventPublisher, never())
        .publishConfirmed(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("PG가 결제를 거절하면(무과금 거절) 예외가 전파되고 원본 코드/사유까지 FAILED 이력에 남긴다")
  void execute_records_failed_when_pg_rejected() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    Long seatId = 200L;
    Long amount = 55_000L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, seatId, PaymentProvider.TOSS, amount, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, "PENDING");
    given(paymentApprovalClientRouter.approve(any()))
        .willThrow(
            new PgRejectionException(
                ErrorStatus.PAYMENT_METHOD_REJECTED, "REJECT_CARD_COMPANY", "카드사에서 거절한 카드입니다"));

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_METHOD_REJECTED);

    ArgumentCaptor<Payment> failedCaptor = ArgumentCaptor.forClass(Payment.class);
    verify(paymentRepository).saveAndFlush(failedCaptor.capture());
    Payment failed = failedCaptor.getValue();
    assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
    // 내부 카테고리 코드/사유는 기존대로 유지되고(#297), PG 원본 코드/사유가 함께 저장된다(#332).
    assertThat(failed.getFailureCode()).isEqualTo(ErrorStatus.PAYMENT_METHOD_REJECTED.getCode());
    assertThat(failed.getPgFailureCode()).isEqualTo("REJECT_CARD_COMPANY");
    assertThat(failed.getPgFailureReason()).isEqualTo("카드사에서 거절한 카드입니다");
    verify(paymentEventPublisher, never())
        .publishConfirmed(any(), any(), any(), any(), any(), any());

    // 화이트리스트 실패는 result=failure & reason 태그 카운터가 증가해야 한다.
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.PAYMENT_CONFIRM,
                    MetricNames.TAG_RESULT,
                    MetricNames.RESULT_FAILURE,
                    MetricNames.TAG_REASON,
                    ErrorStatus.PAYMENT_METHOD_REJECTED.getCode())
                .count())
        .isEqualTo(1.0);

    // approve()가 예외를 던져도 finally에서 PG 승인 Latency 타이머는 기록되어야 한다(예외 안전성).
    assertThat(
            meterRegistry
                .timer(MetricNames.PAYMENT_PG_APPROVE, MetricNames.TAG_PROVIDER, "TOSS")
                .count())
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("원본 없는 화이트리스트 실패(순수 BusinessException)는 PG 원본 필드를 null로 남긴다")
  void execute_records_failed_with_null_pg_fields_when_no_raw() {
    // given: PgRejectionException이 아닌 순수 BusinessException(원본 미확보)이 던져진 경우
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, "PENDING");
    given(paymentApprovalClientRouter.approve(any()))
        .willThrow(new BusinessException(ErrorStatus.PAYMENT_LIMIT_EXCEEDED));

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_LIMIT_EXCEEDED);

    ArgumentCaptor<Payment> failedCaptor = ArgumentCaptor.forClass(Payment.class);
    verify(paymentRepository).saveAndFlush(failedCaptor.capture());
    Payment failed = failedCaptor.getValue();
    assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
    assertThat(failed.getFailureCode()).isEqualTo(ErrorStatus.PAYMENT_LIMIT_EXCEEDED.getCode());
    assertThat(failed.getPgFailureCode()).isNull();
    assertThat(failed.getPgFailureReason()).isNull();
  }

  @Test
  @DisplayName("booking당 FAILED 이력이 상한에 도달하면 화이트리스트 실패라도 저장하지 않고 억제 메트릭만 남긴다(#333)")
  void execute_does_not_record_failed_when_cap_reached() {
    // given
    final Long userId = 10L;
    Long bookingId = 100L;
    final PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, "PENDING");
    given(paymentApprovalClientRouter.approve(any()))
        .willThrow(new BusinessException(ErrorStatus.PAYMENT_METHOD_REJECTED));
    // 이미 상한(5)만큼 FAILED 이력이 쌓여 있다.
    given(paymentRepository.countByBookingIdAndStatus(bookingId, PaymentStatus.FAILED))
        .willReturn(5L);

    // when & then: 결제 실패 예외 자체는 그대로 전파된다.
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_METHOD_REJECTED);

    // 상한 초과라 FAILED row는 저장하지 않는다.
    verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    // 대신 억제 메트릭이 사유 태그와 함께 1 증가한다.
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.PAYMENT_FAILED_RECORD_SUPPRESSED,
                    MetricNames.TAG_REASON,
                    ErrorStatus.PAYMENT_METHOD_REJECTED.getCode())
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("booking당 FAILED 이력이 상한 직전(4건)이면 이번 실패는 저장되고 억제 메트릭은 증가하지 않는다(#333 경계)")
  void execute_records_failed_when_below_cap() {
    // given
    final Long userId = 10L;
    Long bookingId = 100L;
    final PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, "PENDING");
    given(paymentApprovalClientRouter.approve(any()))
        .willThrow(new BusinessException(ErrorStatus.PAYMENT_METHOD_REJECTED));
    // 상한(5) 직전인 4건 → 이번 실패는 5번째로 기록되어야 한다.
    given(paymentRepository.countByBookingIdAndStatus(bookingId, PaymentStatus.FAILED))
        .willReturn(4L);

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_METHOD_REJECTED);

    ArgumentCaptor<Payment> failedCaptor = ArgumentCaptor.forClass(Payment.class);
    verify(paymentRepository).saveAndFlush(failedCaptor.capture());
    assertThat(failedCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
    // 억제 메트릭은 증가하지 않는다.
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.PAYMENT_FAILED_RECORD_SUPPRESSED,
                    MetricNames.TAG_REASON,
                    ErrorStatus.PAYMENT_METHOD_REJECTED.getCode())
                .count())
        .isEqualTo(0.0);
  }

  @Test
  @DisplayName("PG 통신 실패(결과 불명)는 고아 청구 위험이 있어 FAILED 이력을 남기지 않는다")
  void execute_does_not_record_when_pg_communication_failed() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, "PENDING");
    given(paymentApprovalClientRouter.approve(any()))
        .willThrow(new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED));

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);

    verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    verify(paymentEventPublisher, never())
        .publishConfirmed(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("동일 bookingId의 COMPLETED 결제가 이미 존재하면 PAYMENT_409_001 예외가 발생한다")
  void execute_fail_when_already_completed() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.KAKAO, 55_000L, "pgKey_xyz");

    given(paymentRepository.existsByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED))
        .willReturn(true);

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_ALREADY_COMPLETED);

    verify(paymentApprovalClientRouter, never()).approve(any());
    verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    verify(paymentEventPublisher, never())
        .publishConfirmed(any(), any(), any(), any(), any(), any());
    // 첫 가드에서 끝나므로 booking-service까지 가지 않는다.
    verify(bookingRestClient, never()).getBooking(any());
  }

  @Test
  @DisplayName(
      "expired_booking fast-path가 적중하면 BOOKING_409_003으로 막고 booking-service를 호출하지 않는다(#490 역할 구분)")
  void execute_fail_when_booking_expired() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.KAKAO, 55_000L, "pgKey_xyz");

    given(paymentRepository.existsByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED))
        .willReturn(false);
    given(expiredBookingRepository.existsByBookingId(bookingId)).willReturn(true);

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.BOOKING_EXPIRED);

    verify(paymentApprovalClientRouter, never()).approve(any());
    verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    verify(paymentEventPublisher, never())
        .publishConfirmed(any(), any(), any(), any(), any(), any());
    // 로컬 조회로 이미 걸러진 건은 네트워크 왕복을 쓰지 않는다 — fast-path를 남겨 둔 이유다.
    verify(bookingRestClient, never()).getBooking(any());
  }

  @Test
  @DisplayName("동기 확인에서 EXPIRED면 BOOKING_409_003으로 막고 PG 승인을 호출하지 않는다(#490)")
  void execute_fail_when_booking_status_expired() {
    // given: 만료 이벤트가 아직 도착하지 않아 fast-path는 통과하는 상황
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, "EXPIRED");

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.BOOKING_EXPIRED);

    verify(paymentApprovalClientRouter, never()).approve(any());
    verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    verify(paymentEventPublisher, never())
        .publishConfirmed(any(), any(), any(), any(), any(), any());
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.PAYMENT_CONFIRM_BOOKING_GUARD_BLOCKED,
                    MetricNames.TAG_REASON,
                    "EXPIRED")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("사용자가 직접 취소한 CANCELED 예매도 막는다 — 차단 목록이었다면 샜을 경로다(#559)")
  void execute_fail_when_booking_status_canceled() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, "CANCELED");

    // when & then: 만료가 아니므로 "만료된 예매"가 아니라 "확정할 수 없는 예매 상태"로 답한다.
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.BOOKING_CONFIRM_NOT_ALLOWED);

    verify(paymentApprovalClientRouter, never()).approve(any());
    verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.PAYMENT_CONFIRM_BOOKING_GUARD_BLOCKED,
                    MetricNames.TAG_REASON,
                    "CANCELED")
                .count())
        .isEqualTo(1.0);
  }

  @ParameterizedTest(name = "bookingStatus={0}")
  @ValueSource(strings = {"CONFIRMED", "REFUNDING", "REFUNDED"})
  @DisplayName("PENDING이 아닌 나머지 상태는 모두 BOOKING_409_002로 막는다(허용 목록)")
  void execute_fail_when_booking_status_not_pending(String bookingStatus) {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, bookingStatus);

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.BOOKING_CONFIRM_NOT_ALLOWED);

    verify(paymentApprovalClientRouter, never()).approve(any());
    verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
  }

  @Test
  @DisplayName("알 수 없는 상태 문자열도 막고 메트릭은 unknown으로 접는다 — booking이 상태를 늘려도 통과하지 않는다")
  void execute_fail_when_booking_status_unknown() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, "SOMETHING_NEW");

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.BOOKING_CONFIRM_NOT_ALLOWED);

    verify(paymentApprovalClientRouter, never()).approve(any());
    // 태그 카디널리티가 외부 문자열을 따라 늘지 않도록 알려지지 않은 값은 unknown으로 접는다.
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.PAYMENT_CONFIRM_BOOKING_GUARD_BLOCKED,
                    MetricNames.TAG_REASON,
                    "unknown")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("상태 문자열이 null이어도 409로 막는다 — 원시 500이 새면 fail-closed 계약이 깨진다")
  void execute_fail_when_booking_status_null() {
    // given: booking이 필드명을 바꾸거나 URL 오설정으로 다른 200 응답이 오면 상태가 조용히 null이 된다.
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, null);

    // when & then: NPE(500)가 아니라 BusinessException(409)이어야 한다.
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.BOOKING_CONFIRM_NOT_ALLOWED);

    verify(paymentApprovalClientRouter, never()).approve(any());
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.PAYMENT_CONFIRM_BOOKING_GUARD_BLOCKED,
                    MetricNames.TAG_REASON,
                    "unknown")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("존재하지 않는 예매면 BOOKING_404_001이 그대로 전파되고 not_found로 집계된다")
  void execute_fail_when_booking_not_found() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    given(paymentRepository.existsByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED))
        .willReturn(false);
    given(bookingRestClient.getBooking(bookingId))
        .willThrow(new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.BOOKING_NOT_FOUND);

    verify(paymentApprovalClientRouter, never()).approve(any());
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.PAYMENT_CONFIRM_BOOKING_GUARD_BLOCKED,
                    MetricNames.TAG_REASON,
                    "not_found")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("예매 조회가 실패하면(fail-closed) 503이 전파되고 PG 승인도 FAILED 이력도 남지 않는다")
  void execute_fail_when_booking_lookup_failed() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    given(paymentRepository.existsByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED))
        .willReturn(false);
    given(bookingRestClient.getBooking(bookingId))
        .willThrow(new BusinessException(ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED));

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);

    verify(paymentApprovalClientRouter, never()).approve(any());
    // 가드는 PG 호출 try 블록 밖이라 과금이 없고, 따라서 FAILED 이력 기록 경로를 타지 않는다.
    verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    verify(paymentEventPublisher, never())
        .publishConfirmed(any(), any(), any(), any(), any(), any());
    // 조회 실패로 막힌 건도 차단 카운터에 잡혀야 booking 장애 구간이 관측된다.
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.PAYMENT_CONFIRM_BOOKING_GUARD_BLOCKED,
                    MetricNames.TAG_REASON,
                    "lookup_failed")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("예매 소유자가 아니면 BOOKING_404_001로 막고 PG 승인·저장·이벤트가 모두 없다")
  void execute_fail_when_booking_owner_mismatch() {
    // given: 인증 주체(99L)와 예매 소유자(10L)가 다르다. 상태는 정상(PENDING)이라 소유자 가드만이 이 요청을 막는다.
    Long requesterId = 99L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, DEFAULT_OWNER_ID, "PENDING");

    // when & then: 남의 예매 존재가 새지 않도록 "없는 예매"와 같은 응답으로 수렴한다.
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(requesterId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.BOOKING_NOT_FOUND);

    // 과금·귀속이 시작조차 되지 않아야 한다. 하나라도 새면 이 이슈가 막으려던 결과가 그대로 만들어진다.
    verify(paymentApprovalClientRouter, never()).approve(any());
    verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    verify(paymentEventPublisher, never())
        .publishConfirmed(any(), any(), any(), any(), any(), any());
    // 응답이 "예매 없음"으로 동일화됐으므로 소유자 차단을 구분하는 축은 이 태그뿐이다.
    assertThat(guardBlockedCount("owner_mismatch")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("남의 예매가 EXPIRED여도 404로 막는다 — 상태를 먼저 보면 예매의 존재와 상태가 함께 샌다")
  void execute_fail_when_owner_mismatch_precedes_status_check() {
    // given: 소유자도 다르고 상태도 결제 불가(EXPIRED)라, 어느 가드가 먼저 끊는지가 응답으로 드러난다.
    Long requesterId = 99L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, DEFAULT_OWNER_ID, "EXPIRED");

    // when & then: BOOKING_EXPIRED("만료된 예매입니다")가 나가면 남의 예매가 존재하고 만료됐다는 사실이 새어 나간다.
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(requesterId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.BOOKING_NOT_FOUND);

    verify(paymentApprovalClientRouter, never()).approve(any());
    // 상태 갈래로는 세지 않는다 — 소유자 대조가 먼저 끊었기 때문이다. 순서가 뒤집히면 이 단언이 깨진다.
    assertThat(guardBlockedCount("EXPIRED")).isEqualTo(0.0);
    assertThat(guardBlockedCount("owner_mismatch")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("예매 응답에 소유자가 없으면 503으로 끊는다 — 계약 결함을 '예매 없음'으로 감추지 않는다")
  void execute_fail_when_booking_owner_unknown() {
    // given: booking이 user_id 필드를 바꾸거나 지우면 ignoreUnknown 매핑이라 예외 없이 조용히 null이 된다.
    Long requesterId = 99L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, null, "PENDING");

    // when & then: 대조가 불가능한 것이지 예매가 없는 게 아니다. 404로 접으면 전건 실패가 예매 데이터 문제로 오진단된다.
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(requesterId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);

    verify(paymentApprovalClientRouter, never()).approve(any());
    verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    // 사용자 오류(owner_mismatch)와 갈라 세야 배포 사고가 사용자 오류 시계열에 묻히지 않는다.
    assertThat(guardBlockedCount("owner_unknown")).isEqualTo(1.0);
    assertThat(guardBlockedCount("owner_mismatch")).isEqualTo(0.0);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  @DisplayName("예매 응답에 예매번호가 없으면 PG 승인을 시도하지 않고 503으로 끊는다")
  void execute_fail_when_booking_number_unknown(String bookingNumber) {
    // given: booking이 필드명을 바꾸거나 예매번호를 내려주기 전 버전으로 롤백되면 조용히 null이 된다.
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, DEFAULT_OWNER_ID, "PENDING", bookingNumber);

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(DEFAULT_OWNER_ID, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);

    // 이 가드의 존재 이유가 "과금 앞에서 끊기"다. 빈 orderId를 그대로 보내면 PG가 사유를 밝히지 않는
    // 거절로 끊어 원인 파악이 어려워지고, 그 사이 과금이 일어날 여지도 생긴다.
    verify(paymentApprovalClientRouter, never()).approve(any());
    verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    assertThat(guardBlockedCount("booking_number_unknown")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("예매번호가 없어도 상태 판정이 먼저다 — 만료된 예매는 만료로 답한다")
  void execute_fail_when_status_check_precedes_booking_number_check() {
    // given: 만료된 예매이면서 예매번호까지 비어 있는 경우. 둘 다 차단 사유지만 순서가 계약이다(#572).
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, DEFAULT_OWNER_ID, "EXPIRED", null);

    // when & then: 예매번호 검사가 앞으로 가면 만료 예매가 503을 받게 되어 사유가 바뀐다.
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(DEFAULT_OWNER_ID, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.BOOKING_EXPIRED);

    verify(paymentApprovalClientRouter, never()).approve(any());
    assertThat(guardBlockedCount("EXPIRED")).isEqualTo(1.0);
    assertThat(guardBlockedCount("booking_number_unknown")).isEqualTo(0.0);
  }

  @Test
  @DisplayName("예매번호가 없어도 소유자 대조가 먼저다 — 남의 예매는 503이 아니라 404로 답한다")
  void execute_fail_when_owner_check_precedes_booking_number_check() {
    // given: 남의 예매이면서 예매번호까지 비어 있는 경우. 클래스 Javadoc이 소유자·상태 두 축을 모두
    // 예매번호 검사보다 앞이라고 선언하므로(#572·#662), 상태 축과 별개로 소유자 축도 고정한다.
    Long requesterId = 99L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, DEFAULT_OWNER_ID, "PENDING", null);

    // when & then: 예매번호 검사가 소유자 대조 앞으로 가면 남의 예매가 503을 받게 되고, 그 503은
    // "그 bookingId가 존재한다"는 사실을 404와 구분되게 흘린다(#572가 닫은 축).
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(requesterId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.BOOKING_NOT_FOUND);

    verify(paymentApprovalClientRouter, never()).approve(any());
    assertThat(guardBlockedCount("owner_mismatch")).isEqualTo(1.0);
    assertThat(guardBlockedCount("booking_number_unknown")).isEqualTo(0.0);
  }

  @Test
  @DisplayName("PG 주문번호는 픽스처가 세운 예매번호를 그대로 따라간다 — 값이 바뀌면 함께 바뀐다")
  void execute_passes_booking_number_to_pg_as_is() {
    // given: 기본 픽스처(DEFAULT_BOOKING_NUMBER)와 일부러 다른 값을 쓴다. 같은 값이면 orderId가
    // 픽스처를 따라간 것인지 어딘가에 하드코딩된 것인지 구분되지 않는다. 형식은
    // BookingNumberGenerator가 만드는 실제 형태(XXXXX-XXXXX 11자)를 따른다.
    String bookingNumber = "8J4KM-TZP97";
    Long bookingId = 100L;
    final PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.TOSS, 55_000L, "pgKey_xyz");

    givenPreConfirmGuards(bookingId, DEFAULT_OWNER_ID, "PENDING", bookingNumber);
    given(paymentApprovalClientRouter.approve(any(PaymentApprovalRequest.class)))
        .willReturn(new PaymentApprovalResponse("APR-1", 55_000L, LocalDateTime.now(), "카드"));
    given(paymentRepository.saveAndFlush(any(Payment.class))).willAnswer(inv -> inv.getArgument(0));

    // when
    paymentConfirmUseCase.execute(DEFAULT_OWNER_ID, request);

    // then: payment가 자체 규칙으로 주문번호를 만들면 이 단언이 깨진다. 값을 다듬는 가공(자르기·패딩
    // 등)이 들어가도 마찬가지다 — PG는 결제창에 실린 값과 한 글자라도 다르면 승인하지 않는다.
    ArgumentCaptor<PaymentApprovalRequest> captor =
        ArgumentCaptor.forClass(PaymentApprovalRequest.class);
    verify(paymentApprovalClientRouter).approve(captor.capture());
    assertThat(captor.getValue().orderId()).isEqualTo(bookingNumber);
  }

  @Test
  @DisplayName("getConfirmedResponseByBookingId는 bookingId로 먼저 확정된 COMPLETED 결제를 찾아 멱등 응답을 반환한다")
  void getConfirmedResponseByBookingId_returns_existing() throws Exception {
    // given
    Long bookingId = 100L;
    LocalDateTime paidAt = LocalDateTime.of(2026, 5, 22, 10, 0);
    Payment existing =
        Payment.builder()
            .bookingId(bookingId)
            .userId(10L)
            .seatId(200L)
            .provider(PaymentProvider.TOSS)
            .amount(55_000L)
            .status(PaymentStatus.COMPLETED)
            .paymentKey("pgKey_xyz")
            .approvalNumber("APR-1")
            .paidAt(paidAt)
            .build();
    setId(existing, 999L);
    given(paymentRepository.findFirstByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED))
        .willReturn(Optional.of(existing));

    // when
    PaymentConfirmResponse response =
        paymentConfirmUseCase.getConfirmedResponseByBookingId(bookingId);

    // then
    assertThat(response.paymentId()).isEqualTo(999L);
    assertThat(response.status()).isEqualTo("COMPLETED");
    assertThat(response.paidAt()).isEqualTo(paidAt);
  }

  @Test
  @DisplayName("getConfirmedResponseByBookingId는 결제를 찾지 못하면 PAYMENT_404_002 예외가 발생한다")
  void getConfirmedResponseByBookingId_throws_when_absent() {
    // given
    Long bookingId = 404L;
    given(paymentRepository.findFirstByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED))
        .willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.getConfirmedResponseByBookingId(bookingId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_NOT_FOUND);
  }
}
