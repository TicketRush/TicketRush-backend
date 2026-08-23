package com.ticketrush.boundedcontext.payment.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.payment.app.usecase.PaymentRefundByBookingUseCase.RefundOutcome;
import com.ticketrush.boundedcontext.payment.domain.entity.ExpiredBooking;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
import com.ticketrush.boundedcontext.payment.domain.types.RefundTrigger;
import com.ticketrush.boundedcontext.payment.out.apiclient.BookingRestClient;
import com.ticketrush.boundedcontext.payment.out.apiclient.dto.BookingInfoResponse;
import com.ticketrush.boundedcontext.payment.out.repository.ExpiredBookingRepository;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.boundedcontext.payment.out.repository.RefundRepository;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 과금-만료 자동 환불의 판정 순서와 커서 전진을 고정한다 (#607).
 *
 * <p>이 UseCase 의 위험은 "환불하지 않아야 할 건을 환불하는 것"이라, 테스트 대부분이 <b>환불이 일어나지 않음</b>을 단언한다. 특히 판정 순서는 성능이 아니라
 * 안전 계약이다 — 앞 단계에서 막힌 건이 뒤 단계에 도달하지 않아야 PG 를 불필요하게 두드리지 않고, 예매번호 없이 환불이 나가지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentRecoverChargedExpiredBookingUseCaseTest {

  private static final int BATCH_SIZE = 3;
  private static final int GRACE_MINUTES = 30;
  private static final int MAX_REFUNDS_PER_LAP = 100;
  private static final Long BOOKING_ID = 100L;
  private static final String BOOKING_NUMBER = "BOOK-1";

  /** grace 를 넉넉히 넘긴 만료 시각. 실제 시각에 의존하지 않도록 now 기준 상대값으로 만든다. */
  private static final LocalDateTime LONG_EXPIRED = LocalDateTime.now().minusHours(2);

  @Mock private ExpiredBookingRepository expiredBookingRepository;
  @Mock private PaymentRepository paymentRepository;
  @Mock private RefundRepository refundRepository;
  @Mock private BookingRestClient bookingRestClient;
  @Mock private PaymentRefundByBookingUseCase paymentRefundByBookingUseCase;

  private SimpleMeterRegistry meterRegistry;
  private PaymentRecoverChargedExpiredBookingUseCase paymentRecoverChargedExpiredBookingUseCase;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    paymentRecoverChargedExpiredBookingUseCase =
        new PaymentRecoverChargedExpiredBookingUseCase(
            expiredBookingRepository,
            paymentRepository,
            refundRepository,
            bookingRestClient,
            paymentRefundByBookingUseCase,
            meterRegistry);
  }

  private void execute() {
    execute(GRACE_MINUTES, MAX_REFUNDS_PER_LAP);
  }

  private void execute(int graceMinutes, int maxRefundsPerLap) {
    paymentRecoverChargedExpiredBookingUseCase.execute(BATCH_SIZE, graceMinutes, maxRefundsPerLap);
  }

  private ExpiredBooking expiredBooking(Long id, Long bookingId, LocalDateTime expiredAt) {
    ExpiredBooking expiredBooking = ExpiredBooking.of(bookingId, expiredAt);
    ReflectionTestUtils.setField(expiredBooking, "id", id);
    return expiredBooking;
  }

  private void givenTargets(long idAfter, List<ExpiredBooking> targets) {
    given(
            expiredBookingRepository.findByIdGreaterThanOrderByIdAsc(
                eq(idAfter), any(Pageable.class)))
        .willReturn(targets);
  }

  /** 해당 bookingId 들이 COMPLETED 결제를 가진 것으로 스텁한다. */
  private void givenCharged(Long... bookingIds) {
    List<Payment> payments =
        Arrays.stream(bookingIds)
            .map(
                bookingId -> {
                  Payment payment = mock(Payment.class);
                  given(payment.getBookingId()).willReturn(bookingId);
                  return payment;
                })
            .toList();
    givenChargedPayments(payments);
  }

  private void givenChargedPayments(List<Payment> payments) {
    given(paymentRepository.findByBookingIdInAndStatus(any(), eq(PaymentStatus.COMPLETED)))
        .willReturn(payments);
  }

  private void givenNoRefundHistory() {
    given(refundRepository.findByBookingIdIn(any())).willReturn(List.of());
  }

  private void givenRefundHistory(Long bookingId, RefundStatus status) {
    Refund refund = mock(Refund.class);
    given(refund.getBookingId()).willReturn(bookingId);
    given(refund.getStatus()).willReturn(status);
    given(refundRepository.findByBookingIdIn(any())).willReturn(List.of(refund));
  }

  private void givenBooking(Long bookingId, String status, String bookingNumber) {
    given(bookingRestClient.getBooking(bookingId))
        .willReturn(new BookingInfoResponse(bookingId, 10L, status, bookingNumber));
  }

  private double skipCount(String reason) {
    return meterRegistry
        .counter(MetricNames.PAYMENT_CHARGED_EXPIRED_SKIPPED, MetricNames.TAG_REASON, reason)
        .count();
  }

  private double detectedCount() {
    return meterRegistry.counter(MetricNames.PAYMENT_CHARGED_EXPIRED_DETECTED).count();
  }

  private double recoveredCount() {
    return meterRegistry.counter(MetricNames.PAYMENT_CHARGED_EXPIRED_RECOVERED).count();
  }

  private List<Long> capturedCursors(int laps) {
    ArgumentCaptor<Long> cursorCaptor = ArgumentCaptor.forClass(Long.class);
    verify(expiredBookingRepository, times(laps))
        .findByIdGreaterThanOrderByIdAsc(cursorCaptor.capture(), any(Pageable.class));
    return cursorCaptor.getAllValues();
  }

  @Test
  @DisplayName("성공: 과금됐는데 만료된 예매를 예매번호와 함께 자동 환불한다")
  void refunds_charged_expired_booking() {
    // given
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LONG_EXPIRED)));
    givenCharged(BOOKING_ID);
    givenNoRefundHistory();
    givenBooking(BOOKING_ID, "EXPIRED", BOOKING_NUMBER);
    given(
            paymentRefundByBookingUseCase.execute(
                BOOKING_ID, BOOKING_NUMBER, RefundTrigger.CONFIRM_SIGNAL_LOST))
        .willReturn(RefundOutcome.REFUNDED);

    // when
    execute();

    // then
    verify(paymentRefundByBookingUseCase)
        .execute(BOOKING_ID, BOOKING_NUMBER, RefundTrigger.CONFIRM_SIGNAL_LOST);
    assertThat(detectedCount()).isEqualTo(1);
    assertThat(recoveredCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("성공: 만료됐지만 과금되지 않은 건은 후보로 세지 않는다")
  void ignores_expired_booking_without_payment() {
    // given — COMPLETED 결제가 하나도 없다
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LONG_EXPIRED)));
    givenChargedPayments(List.of());

    // then — 후보가 없으면 환불 이력 조회조차 하지 않는다(랩당 풀스캔 1회도 아끼는 자리)
    execute();

    verifyNoInteractions(refundRepository, bookingRestClient, paymentRefundByBookingUseCase);
    assertThat(detectedCount()).isZero();
  }

  @Test
  @DisplayName("판정 순서: 이미 환불된 건은 grace 를 따지기도 전에 걸러진다")
  void already_refunded_is_checked_before_grace() {
    // given — 두 조건이 동시에 참인 건을 만든다. 방금 만료됐고(grace 미경과) 환불 이력도 있다.
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LocalDateTime.now().minusMinutes(1))));
    givenCharged(BOOKING_ID);
    givenRefundHistory(BOOKING_ID, RefundStatus.COMPLETED);

    // when
    execute();

    // then — 판정 1(환불 이력)이 판정 2(grace)보다 먼저다. 순서가 뒤집히면 grace 로 세어져 이 단언이 깨진다.
    // 이 순서는 PG 무한 재호출 차단의 핵심이라 성능이 아니라 안전 계약이다.
    assertThat(skipCount("already_refunded")).isEqualTo(1);
    assertThat(skipCount("grace")).isZero();
    verifyNoInteractions(bookingRestClient, paymentRefundByBookingUseCase);
  }

  @Test
  @DisplayName("판정 순서: 이미 환불 이력이 있으면 booking 을 조회하지도 PG 를 부르지도 않는다")
  void skips_already_refunded_before_lookup() {
    // given
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LONG_EXPIRED)));
    givenCharged(BOOKING_ID);
    givenRefundHistory(BOOKING_ID, RefundStatus.COMPLETED);

    // when
    execute();

    // then — 선검사가 뒤로 새면 실패한 환불을 매 주기 PG 에 되풀이해 던지게 된다
    verifyNoInteractions(bookingRestClient, paymentRefundByBookingUseCase);
    assertThat(skipCount("already_refunded")).isEqualTo(1);
    assertThat(detectedCount()).isZero();
  }

  @Test
  @DisplayName("🔴 관측: PG 거절로 FAILED 이력만 남은 건은 정상 환불과 다른 태그로 센다")
  void separates_failed_refund_history_from_settled() {
    // given
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LONG_EXPIRED)));
    givenCharged(BOOKING_ID);
    givenRefundHistory(BOOKING_ID, RefundStatus.FAILED);

    // when
    execute();

    // then — 둘 다 "환불 이력이 있어 건너뜀"이지만 뜻이 정반대다. 이쪽은 돈이 돌아가지 않은 미해결 사고이고,
    // 예매가 EXPIRED 라 booking 의 관리자 재환불 게이트도 열리지 않아 이 태그가 유일한 창구다.
    assertThat(skipCount("refund_failed_history")).isEqualTo(1);
    assertThat(skipCount("already_refunded")).isZero();
    verifyNoInteractions(bookingRestClient, paymentRefundByBookingUseCase);
  }

  @Test
  @DisplayName("성공: 환불이 진행 중(PENDING)인 건도 손대지 않는다")
  void skips_pending_refund() {
    // given
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LONG_EXPIRED)));
    givenCharged(BOOKING_ID);
    givenRefundHistory(BOOKING_ID, RefundStatus.PENDING);

    // when
    execute();

    // then
    verifyNoInteractions(bookingRestClient, paymentRefundByBookingUseCase);
    assertThat(skipCount("already_refunded")).isEqualTo(1);
  }

  @Test
  @DisplayName("판정 순서: 만료 직후(grace 미경과)면 booking 을 조회하지 않고 건너뛴다")
  void skips_within_grace_period() {
    // given — 방금 만료된 건
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LocalDateTime.now().minusMinutes(1))));
    givenCharged(BOOKING_ID);
    givenNoRefundHistory();

    // when
    execute();

    // then
    verifyNoInteractions(bookingRestClient, paymentRefundByBookingUseCase);
    assertThat(skipCount("grace")).isEqualTo(1);
    assertThat(detectedCount()).isZero();
  }

  @Test
  @DisplayName("성공: grace 를 갓 넘긴 건은 환불 대상이 된다")
  void refunds_just_past_grace_boundary() {
    // given — 유예 30분에 만료 31분 전. 경계 바깥이라 통과해야 한다.
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LocalDateTime.now().minusMinutes(31))));
    givenCharged(BOOKING_ID);
    givenNoRefundHistory();
    givenBooking(BOOKING_ID, "EXPIRED", BOOKING_NUMBER);
    given(paymentRefundByBookingUseCase.execute(eq(BOOKING_ID), anyString(), any()))
        .willReturn(RefundOutcome.REFUNDED);

    // when
    execute();

    // then — 경계를 부등호 하나 잘못 쓰면(isAfter ↔ isBefore) 사고 건이 영영 복구되지 않거나
    // 반대로 정상 처리 중인 건까지 환불된다
    assertThat(recoveredCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("fail-closed: booking 조회가 실패하면 환불하지 않는다")
  void does_not_refund_when_booking_lookup_fails() {
    // given
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LONG_EXPIRED)));
    givenCharged(BOOKING_ID);
    givenNoRefundHistory();
    given(bookingRestClient.getBooking(BOOKING_ID))
        .willThrow(new BusinessException(ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED));

    // when
    execute();

    // then — 조회하지 못한 건을 환불하면 잘못된 취소가 되돌릴 수 없게 나간다
    verifyNoInteractions(paymentRefundByBookingUseCase);
    assertThat(skipCount("booking_lookup_failed")).isEqualTo(1);
    // 자동 복구가 닿지 못한 건이므로 발견 수에는 포함된다(detected − recovered = 미복구 잔량)
    assertThat(detectedCount()).isEqualTo(1);
    assertThat(recoveredCount()).isZero();
  }

  @Test
  @DisplayName("fail-closed: booking 이 없는 예매(404)여도 환불하지 않는다")
  void does_not_refund_when_booking_not_found() {
    // given
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LONG_EXPIRED)));
    givenCharged(BOOKING_ID);
    givenNoRefundHistory();
    given(bookingRestClient.getBooking(BOOKING_ID))
        .willThrow(new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    // when
    execute();

    // then
    verifyNoInteractions(paymentRefundByBookingUseCase);
    assertThat(skipCount("booking_lookup_failed")).isEqualTo(1);
  }

  @Test
  @DisplayName("🔴 조회가 BusinessException 밖의 예외로 깨져도 남은 후보와 메트릭이 살아남는다")
  void unexpected_lookup_exception_does_not_abort_lap() {
    // given — 예: 내부 API 토큰 미주입으로 헤더 설정에서 NPE. 첫 건에서 터지고 뒤 건은 정상이다.
    givenTargets(
        0L,
        List.of(expiredBooking(1L, 100L, LONG_EXPIRED), expiredBooking(2L, 200L, LONG_EXPIRED)));
    givenCharged(100L, 200L);
    givenNoRefundHistory();
    given(bookingRestClient.getBooking(100L)).willThrow(new NullPointerException("token is null"));
    givenBooking(200L, "EXPIRED", "BOOK-200");
    given(paymentRefundByBookingUseCase.execute(eq(200L), anyString(), any()))
        .willReturn(RefundOutcome.REFUNDED);

    // when
    execute();

    // then — 예외가 새면 랩 전체가 중단될 뿐 아니라 detected 카운터와 경고 로그까지 통째로 실행되지 않아,
    // "사고가 있었다"는 유일한 관측 축이 하필 그 장애 구간에서 침묵한다
    verify(paymentRefundByBookingUseCase).execute(eq(200L), anyString(), any());
    assertThat(skipCount("booking_lookup_failed")).isEqualTo(1);
    assertThat(detectedCount()).isEqualTo(2);
    assertThat(recoveredCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("fail-closed: 응답에 예매 상태가 없어도 NPE 없이 환불을 보류한다")
  void does_not_refund_when_booking_status_is_null() {
    // given — ignoreUnknown 이라 booking 이 필드명을 바꾸면 예외 없이 null 이 된다
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LONG_EXPIRED)));
    givenCharged(BOOKING_ID);
    givenNoRefundHistory();
    givenBooking(BOOKING_ID, null, BOOKING_NUMBER);

    // when
    execute();

    // then — Set.of(...).contains(null) 이 NPE 를 던지므로 상태 판정보다 먼저 갈라야 한다
    verifyNoInteractions(paymentRefundByBookingUseCase);
    assertThat(skipCount("booking_lookup_failed")).isEqualTo(1);
  }

  @Test
  @DisplayName("성공: 예매가 살아 있으면(CONFIRMED) 환불하지 않는다")
  void does_not_refund_confirmed_booking() {
    // given — 로컬 만료 기록이 낡았거나 잘못 들어온 경우
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LONG_EXPIRED)));
    givenCharged(BOOKING_ID);
    givenNoRefundHistory();
    givenBooking(BOOKING_ID, "CONFIRMED", BOOKING_NUMBER);

    // when
    execute();

    // then
    verifyNoInteractions(paymentRefundByBookingUseCase);
    assertThat(skipCount("booking_alive")).isEqualTo(1);
  }

  @Test
  @DisplayName("🔴 관측: 모르는 예매 상태는 '살아 있음'과 다른 태그로 센다")
  void separates_unknown_booking_status() {
    // given — booking 이 상태 이름을 바꾼 배포 사고를 흉내낸다
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LONG_EXPIRED)));
    givenCharged(BOOKING_ID);
    givenNoRefundHistory();
    givenBooking(BOOKING_ID, "SETTLED", BOOKING_NUMBER);

    // when
    execute();

    // then — 한 태그로 접으면 전건이 "예매가 살아 있어서 건너뜀"으로 보고되고, 그동안 사고 복구는
    // 전면 정지한다. 안전하게 깨지는 것과 깨진 것이 보이는 것은 다른 문제다(#572 선례).
    verifyNoInteractions(paymentRefundByBookingUseCase);
    assertThat(skipCount("booking_status_unknown")).isEqualTo(1);
    assertThat(skipCount("booking_alive")).isZero();
  }

  @Test
  @DisplayName("성공: 사용자가 먼저 취소한(CANCELED) 예매도 환불 대상이다")
  void refunds_canceled_booking() {
    // given
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LONG_EXPIRED)));
    givenCharged(BOOKING_ID);
    givenNoRefundHistory();
    givenBooking(BOOKING_ID, "CANCELED", BOOKING_NUMBER);
    given(
            paymentRefundByBookingUseCase.execute(
                BOOKING_ID, BOOKING_NUMBER, RefundTrigger.CONFIRM_SIGNAL_LOST))
        .willReturn(RefundOutcome.REFUNDED);

    // when
    execute();

    // then — 결과가 같다(과금이 남고 예매가 없다)
    assertThat(recoveredCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("🔴 회귀 그물: 예매번호를 얻지 못하면 환불하지 않는다")
  void does_not_refund_when_booking_number_is_missing() {
    // given — 배포 순서 역전으로 booking 이 아직 이 필드를 내려주지 않는 구간을 포함한다
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LONG_EXPIRED)));
    givenCharged(BOOKING_ID);
    givenNoRefundHistory();
    givenBooking(BOOKING_ID, "EXPIRED", null);

    // when
    execute();

    // then — 값 없이 환불하면 PaymentCanceledEvent 가 bookingNumber=null 로 나가고, seat 의 좌석 소유
    // 교차검증이 통째로 꺼져 그 사이 다른 사용자에게 팔린 SOLD 좌석을 반환할 수 있다(#608)
    verifyNoInteractions(paymentRefundByBookingUseCase);
    assertThat(skipCount("booking_number_unknown")).isEqualTo(1);
  }

  @Test
  @DisplayName("🔴 회귀 그물: 예매번호가 공백이어도 환불하지 않는다")
  void does_not_refund_when_booking_number_is_blank() {
    // given
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LONG_EXPIRED)));
    givenCharged(BOOKING_ID);
    givenNoRefundHistory();
    givenBooking(BOOKING_ID, "EXPIRED", "   ");

    // when
    execute();

    // then
    verifyNoInteractions(paymentRefundByBookingUseCase);
    assertThat(skipCount("booking_number_unknown")).isEqualTo(1);
  }

  @Test
  @DisplayName("성공: 커서는 환불에 실패한 건에서도 전진한다")
  void advances_cursor_even_when_refund_fails() {
    // given — 선두 건이 PG 통신 실패로 막히고, 뒤 건은 정상
    givenTargets(
        0L,
        List.of(
            expiredBooking(1L, 100L, LONG_EXPIRED),
            expiredBooking(2L, 200L, LONG_EXPIRED),
            expiredBooking(3L, 300L, LONG_EXPIRED)));
    givenCharged(100L, 200L, 300L);
    givenNoRefundHistory();
    givenBooking(100L, "EXPIRED", "BOOK-100");
    givenBooking(200L, "EXPIRED", "BOOK-200");
    givenBooking(300L, "EXPIRED", "BOOK-300");
    willThrow(new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED))
        .given(paymentRefundByBookingUseCase)
        .execute(eq(100L), anyString(), any());
    given(paymentRefundByBookingUseCase.execute(eq(200L), anyString(), any()))
        .willReturn(RefundOutcome.REFUNDED);
    given(paymentRefundByBookingUseCase.execute(eq(300L), anyString(), any()))
        .willReturn(RefundOutcome.REFUNDED);

    // when
    execute();

    // then — 선두 실패가 뒤 건을 굶기지 않는다. #574 의 "연속 실패 시 커서를 두고 중단"을 복제하면
    // 여기서는 그 봉쇄가 위치만 바꿔 재현된다.
    verify(paymentRefundByBookingUseCase).execute(eq(200L), anyString(), any());
    verify(paymentRefundByBookingUseCase).execute(eq(300L), anyString(), any());
    assertThat(recoveredCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("성공: 배치가 가득 차면 다음 주기는 마지막 id 다음부터 읽는다")
  void continues_from_cursor_on_next_lap() {
    // given — 첫 주기가 batchSize 만큼 꽉 찬다(뒤에 더 있을 수 있다)
    givenTargets(
        0L,
        List.of(
            expiredBooking(1L, 100L, LONG_EXPIRED),
            expiredBooking(2L, 200L, LONG_EXPIRED),
            expiredBooking(3L, 300L, LONG_EXPIRED)));
    givenChargedPayments(List.of());
    givenTargets(3L, List.of());

    // when
    execute();
    execute();

    // then — 커서 없이 매번 앞에서 읽으면 만료가 상한을 넘는 순간 뒤쪽 사고 건이 영구히 묻힌다
    assertThat(capturedCursors(2)).containsExactly(0L, 3L);
  }

  @Test
  @DisplayName("성공: 마지막 페이지까지 훑으면 커서를 0으로 되돌린다")
  void resets_cursor_after_last_page() {
    // given — batchSize 보다 적게 돌아오면 마지막 페이지다
    givenTargets(0L, List.of(expiredBooking(1L, 100L, LONG_EXPIRED)));
    givenChargedPayments(List.of());

    // when
    execute();
    execute();

    // then — 되돌리지 않으면 한 바퀴를 돈 뒤 영영 아무것도 보지 않는다
    assertThat(capturedCursors(2)).containsExactly(0L, 0L);
  }

  @Test
  @DisplayName("성공: 대상이 없으면 커서를 되돌리고 아무것도 하지 않는다")
  void resets_cursor_when_no_targets() {
    // given
    givenTargets(0L, List.of());

    // when
    execute();

    // then
    verifyNoInteractions(paymentRepository, refundRepository, bookingRestClient);
  }

  @Test
  @DisplayName("🔴 랩당 환불 상한에 도달하면 조기 종료하고, 미룬 건은 다음 주기가 처리한다")
  void stops_at_max_refunds_per_lap() {
    // given — 후보 2건에 상한 1건. 첫 랩은 마지막 페이지(2건 < batchSize 3)지만 상한에 걸렸으므로
    // 커서를 되돌리면 안 된다. 되돌리면 2번 건이 한 바퀴를 통째로 기다린다.
    givenTargets(
        0L,
        List.of(expiredBooking(1L, 100L, LONG_EXPIRED), expiredBooking(2L, 200L, LONG_EXPIRED)));
    givenCharged(100L, 200L);
    givenNoRefundHistory();
    givenBooking(100L, "EXPIRED", "BOOK-100");
    given(paymentRefundByBookingUseCase.execute(eq(100L), anyString(), any()))
        .willReturn(RefundOutcome.REFUNDED);
    // 두 번째 랩은 커서 1 다음부터 읽어 미뤄 둔 건을 집어든다.
    givenTargets(1L, List.of(expiredBooking(2L, 200L, LONG_EXPIRED)));
    givenBooking(200L, "EXPIRED", "BOOK-200");
    given(paymentRefundByBookingUseCase.execute(eq(200L), anyString(), any()))
        .willReturn(RefundOutcome.REFUNDED);

    // when
    execute(GRACE_MINUTES, 1);
    execute(GRACE_MINUTES, 1);

    // then — Toss 연속 요청 제한(403)에 걸리면 그 건들이 FAILED 이력을 얻어 자동 복구에서 영구히 빠지므로,
    // 첫 랩에 백로그를 몰아 던지지 않는다. 굶지는 않는다 — 다음 주기가 멈춘 자리에서 이어받는다.
    assertThat(capturedCursors(2)).containsExactly(0L, 1L);
    verify(paymentRefundByBookingUseCase).execute(eq(100L), anyString(), any());
    verify(paymentRefundByBookingUseCase).execute(eq(200L), anyString(), any());
    assertThat(recoveredCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("🔴 실패한 환불 시도도 랩 예산을 소모한다")
  void failed_attempts_consume_the_lap_budget() {
    // given — 후보 3건, 상한 2건. 전건이 PG 거절로 실패한다. Toss 연속 요청 제한에 걸린 랩이 정확히
    // 이 모습이다 — 성공이 0건이다.
    givenTargets(
        0L,
        List.of(
            expiredBooking(1L, 100L, LONG_EXPIRED),
            expiredBooking(2L, 200L, LONG_EXPIRED),
            expiredBooking(3L, 300L, LONG_EXPIRED)));
    givenCharged(100L, 200L, 300L);
    givenNoRefundHistory();
    givenBooking(100L, "EXPIRED", "BOOK-100");
    givenBooking(200L, "EXPIRED", "BOOK-200");
    willThrow(new BusinessException(ErrorStatus.PAYMENT_REFUND_FAILED))
        .given(paymentRefundByBookingUseCase)
        .execute(any(), anyString(), any());

    // when
    execute(GRACE_MINUTES, 2);

    // then — 예산을 성공 건수로 세면(outcome == REFUNDED) 이 랩은 상한을 영원히 트립하지 못하고
    // 배치 전체가 PG 로 날아간다. 상한이 필요한 바로 그 상황에서만 무력해지는 셈이라, 세는 기준이
    // "시도"여야 한다는 것이 이 상한의 핵심 계약이다.
    verify(paymentRefundByBookingUseCase, times(2)).execute(any(), anyString(), any());
    assertThat(recoveredCount()).isZero();
    assertThat(detectedCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("성공: 동시 환불이 unique 제약에 막혀도 예외를 전파하지 않고 다음 건을 처리한다")
  void continues_after_unique_violation() {
    // given
    givenTargets(
        0L,
        List.of(expiredBooking(1L, 100L, LONG_EXPIRED), expiredBooking(2L, 200L, LONG_EXPIRED)));
    givenCharged(100L, 200L);
    givenNoRefundHistory();
    givenBooking(100L, "EXPIRED", "BOOK-100");
    givenBooking(200L, "EXPIRED", "BOOK-200");
    willThrow(new DataIntegrityViolationException("duplicate"))
        .given(paymentRefundByBookingUseCase)
        .execute(eq(100L), anyString(), any());
    given(paymentRefundByBookingUseCase.execute(eq(200L), anyString(), any()))
        .willReturn(RefundOutcome.REFUNDED);

    // when
    execute();

    // then — 이미 다른 경로에서 환불이 확정된 멱등 정상이다
    verify(paymentRefundByBookingUseCase).execute(eq(200L), anyString(), any());
    assertThat(recoveredCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("성공: PG 가 환불을 거절하면 실패 카운터를 남기고 다음 건으로 넘어간다")
  void records_failure_metric_on_deterministic_rejection() {
    // given
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LONG_EXPIRED)));
    givenCharged(BOOKING_ID);
    givenNoRefundHistory();
    givenBooking(BOOKING_ID, "EXPIRED", BOOKING_NUMBER);
    willThrow(new BusinessException(ErrorStatus.PAYMENT_REFUND_FAILED))
        .given(paymentRefundByBookingUseCase)
        .execute(eq(BOOKING_ID), anyString(), any());

    // when
    execute();

    // then — trigger 태그가 없으면 이 사고가 사용자 취소 실패와 한 시계열에 섞인다
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.PAYMENT_REFUND_FAILED,
                    MetricNames.TAG_TRIGGER,
                    RefundTrigger.CONFIRM_SIGNAL_LOST.tag())
                .count())
        .isEqualTo(1);
    assertThat(detectedCount()).isEqualTo(1);
    assertThat(recoveredCount()).isZero();
  }

  @Test
  @DisplayName("성공: 이미 정리된 결제(ALREADY_SETTLED)는 복구 성공으로 세지 않는다")
  void does_not_count_already_settled_as_recovered() {
    // given
    givenTargets(0L, List.of(expiredBooking(1L, BOOKING_ID, LONG_EXPIRED)));
    givenCharged(BOOKING_ID);
    givenNoRefundHistory();
    givenBooking(BOOKING_ID, "EXPIRED", BOOKING_NUMBER);
    given(
            paymentRefundByBookingUseCase.execute(
                BOOKING_ID, BOOKING_NUMBER, RefundTrigger.CONFIRM_SIGNAL_LOST))
        .willReturn(RefundOutcome.ALREADY_SETTLED);

    // when
    execute();

    // then — 신호 기반 경로(#492)에서는 전제 붕괴지만 대조 기반인 이 경로에서는 경합의 정상적 귀결이다.
    // 발견은 했으나 복구하지 못한 것이므로 detected 에만 잡힌다.
    assertThat(detectedCount()).isEqualTo(1);
    assertThat(recoveredCount()).isZero();
  }
}
