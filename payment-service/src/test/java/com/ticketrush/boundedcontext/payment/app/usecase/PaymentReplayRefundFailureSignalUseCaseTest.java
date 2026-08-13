package com.ticketrush.boundedcontext.payment.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
import com.ticketrush.boundedcontext.payment.out.repository.RefundRepository;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.eventpublisher.EventReplayPublisher;
import com.ticketrush.shared.payment.event.RefundFailedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class PaymentReplayRefundFailureSignalUseCaseTest {

  private static final int BATCH_SIZE = 3;
  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 8, 13, 10, 0);

  @Mock private RefundRepository refundRepository;
  @Mock private EventReplayPublisher eventReplayPublisher;

  private SimpleMeterRegistry meterRegistry;
  private PaymentReplayRefundFailureSignalUseCase paymentReplayRefundFailureSignalUseCase;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    paymentReplayRefundFailureSignalUseCase =
        new PaymentReplayRefundFailureSignalUseCase(
            refundRepository, eventReplayPublisher, meterRegistry);
    paymentReplayRefundFailureSignalUseCase.registerGauge();
  }

  private Refund failedRefund(Long refundId, Long bookingId, LocalDateTime requestedAt)
      throws Exception {
    Refund refund = Refund.failed(refundId + 1000, bookingId, 10_000L, "환불 실패", requestedAt);
    Field idField = refund.getClass().getSuperclass().getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(refund, refundId);
    return refund;
  }

  private void givenTargets(long idAfter, List<Refund> targets) {
    given(
            refundRepository.findByStatusAndIdGreaterThanOrderByIdAsc(
                eq(RefundStatus.FAILED), eq(idAfter), any(Pageable.class)))
        .willReturn(targets);
  }

  @Test
  @DisplayName("성공: 미해결 FAILED 환불 이력의 보상 신호를 원본 실패 시각으로 재발행한다")
  void replays_signal_with_original_failed_at() throws Exception {
    // given
    given(refundRepository.countByStatus(RefundStatus.FAILED)).willReturn(1L);
    givenTargets(0L, List.of(failedRefund(7L, 100L, FAILED_AT)));

    // when
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);

    // then: 수신 측은 failedAt으로 진행 중인 재환불과의 선후를 판정한다. 지금 시각을 지어내 보내면
    // 진행 중인 시도를 중단시키므로 반드시 원본 시각이어야 한다.
    ArgumentCaptor<RefundFailedEvent> captor = ArgumentCaptor.forClass(RefundFailedEvent.class);
    verify(eventReplayPublisher).republish(captor.capture(), anyString());

    RefundFailedEvent event = captor.getValue();
    assertThat(event.bookingId()).isEqualTo(100L);
    assertThat(event.failedAt()).isEqualTo(FAILED_AT);
    assertThat(event.reason()).isEqualTo("환불 실패");
    // refund 테이블에 bookingNumber가 없어 null이다. 유일한 구독자(booking)가 쓰지 않는다.
    assertThat(event.bookingNumber()).isNull();
  }

  @Test
  @DisplayName("성공: 조회 상한으로 batchSize를 그대로 넘긴다")
  void queries_with_given_batch_size() throws Exception {
    // given: 이 상한이 한 주기의 유일한 부하 방어선이라 계약으로 고정한다.
    given(refundRepository.countByStatus(RefundStatus.FAILED)).willReturn(1L);
    givenTargets(0L, List.of(failedRefund(7L, 100L, FAILED_AT)));

    // when
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);

    // then
    verify(refundRepository)
        .findByStatusAndIdGreaterThanOrderByIdAsc(
            RefundStatus.FAILED, 0L, PageRequest.of(0, BATCH_SIZE));
  }

  @Test
  @DisplayName("성공: 배치가 가득 차면 다음 주기는 마지막 건 다음부터 이어 읽는다")
  void advances_cursor_when_batch_is_full() throws Exception {
    // given: 커서가 없으면 미해결이 batchSize를 넘는 순간 뒤쪽 건이 영영 선택되지 않는다.
    given(refundRepository.countByStatus(RefundStatus.FAILED)).willReturn(10L);
    givenTargets(
        0L,
        List.of(
            failedRefund(7L, 100L, FAILED_AT),
            failedRefund(8L, 200L, FAILED_AT),
            failedRefund(9L, 300L, FAILED_AT)));
    givenTargets(9L, List.of(failedRefund(10L, 400L, FAILED_AT)));

    // when
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);

    // then
    verify(refundRepository)
        .findByStatusAndIdGreaterThanOrderByIdAsc(
            eq(RefundStatus.FAILED), eq(9L), any(Pageable.class));
    verify(eventReplayPublisher, times(4)).republish(any(), anyString());
  }

  @Test
  @DisplayName("성공: 마지막 페이지를 읽으면 다음 주기는 다시 처음부터 훑는다")
  void resets_cursor_after_last_page() throws Exception {
    // given: 배치가 덜 찼다 = 한 바퀴를 돌았다.
    given(refundRepository.countByStatus(RefundStatus.FAILED)).willReturn(1L);
    givenTargets(0L, List.of(failedRefund(7L, 100L, FAILED_AT)));

    // when
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);

    // then: 두 번째도 커서 0으로 조회한다(앞쪽 미해결 건이 계속 재발행 대상에 남는다).
    verify(refundRepository, times(2))
        .findByStatusAndIdGreaterThanOrderByIdAsc(
            eq(RefundStatus.FAILED), eq(0L), any(Pageable.class));
  }

  @Test
  @DisplayName("성공: 같은 환불 이력은 언제 재발행해도 같은 eventId를 쓴다")
  void uses_same_event_id_for_same_refund() throws Exception {
    // given: 식별자가 매번 달라지면 수신 측 Inbox가 걸러내지 못해 재발행마다 트랜잭션이 다시 열린다.
    given(refundRepository.countByStatus(RefundStatus.FAILED)).willReturn(1L);
    givenTargets(0L, List.of(failedRefund(7L, 100L, FAILED_AT)));

    // when
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);

    // then
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(eventReplayPublisher, times(2)).republish(any(), captor.capture());
    assertThat(captor.getAllValues().get(0)).isEqualTo(captor.getAllValues().get(1));
  }

  @Test
  @DisplayName("성공: 서로 다른 환불 이력은 서로 다른 eventId를 쓴다")
  void uses_distinct_event_id_per_refund() throws Exception {
    // given
    given(refundRepository.countByStatus(RefundStatus.FAILED)).willReturn(2L);
    givenTargets(0L, List.of(failedRefund(7L, 100L, FAILED_AT), failedRefund(8L, 200L, FAILED_AT)));

    // when
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);

    // then
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(eventReplayPublisher, times(2)).republish(any(), captor.capture());
    assertThat(captor.getAllValues().get(0)).isNotEqualTo(captor.getAllValues().get(1));
  }

  @Test
  @DisplayName("성공: 실패 시각이 없는 이력은 재발행하지 않는다")
  void skips_refund_without_failed_at() throws Exception {
    // given: 시각을 지어내 보내면 진행 중인 재환불을 중단시킬 수 있다.
    given(refundRepository.countByStatus(RefundStatus.FAILED)).willReturn(1L);
    givenTargets(0L, List.of(failedRefund(7L, 100L, null)));

    // when
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);

    // then
    verify(eventReplayPublisher, never()).republish(any(), anyString());
  }

  @Test
  @DisplayName("성공: 실패 시각이 없어 건너뛴 이력도 커서를 전진시킨다")
  void skipped_refund_still_advances_cursor() throws Exception {
    // given: 건너뛴 자리에서 커서가 멈추면 그 row가 랩 선두를 영구 점유해 뒤쪽 건이 영영 안 나간다.
    given(refundRepository.countByStatus(RefundStatus.FAILED)).willReturn(10L);
    givenTargets(
        0L,
        List.of(
            failedRefund(7L, 100L, null),
            failedRefund(8L, 200L, null),
            failedRefund(9L, 300L, null)));
    givenTargets(9L, List.of(failedRefund(10L, 400L, FAILED_AT)));

    // when
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);

    // then: 두 번째 주기는 건너뛴 세 건 다음부터 읽어 실제 대상에 도달한다.
    verify(refundRepository)
        .findByStatusAndIdGreaterThanOrderByIdAsc(
            eq(RefundStatus.FAILED), eq(9L), any(Pageable.class));
    verify(eventReplayPublisher).republish(any(), anyString());
  }

  @Test
  @DisplayName("성공: 대상이 없으면 아무것도 발행하지 않는다")
  void publishes_nothing_when_no_target() {
    // given: 정상 운영에서 대상은 0으로 수렴한다(해결되면 COMPLETED로 전이돼 빠진다).
    given(refundRepository.countByStatus(RefundStatus.FAILED)).willReturn(0L);
    givenTargets(0L, List.of());

    // when
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);

    // then
    verify(eventReplayPublisher, never()).republish(any(), anyString());
    assertThat(meterRegistry.get(MetricNames.PAYMENT_REFUND_UNRESOLVED).gauge().value()).isZero();
  }

  @Test
  @DisplayName("성공: 미해결 잔량을 Gauge로 노출한다")
  void exposes_unresolved_count_as_gauge() throws Exception {
    // given
    given(refundRepository.countByStatus(RefundStatus.FAILED)).willReturn(3L);
    givenTargets(0L, List.of(failedRefund(7L, 100L, FAILED_AT)));

    // when
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);

    // then: 조회 batch 크기가 아니라 전체 잔량이어야 한다. 0이 아니면 곧 미해결 사고 건수다.
    assertThat(meterRegistry.get(MetricNames.PAYMENT_REFUND_UNRESOLVED).gauge().value())
        .isEqualTo(3.0);
  }

  @Test
  @DisplayName("성공: 한 건의 재발행이 실패해도 나머지 건은 계속 재발행한다")
  void continues_after_single_failure() throws Exception {
    // given
    given(refundRepository.countByStatus(RefundStatus.FAILED)).willReturn(2L);
    givenTargets(0L, List.of(failedRefund(7L, 100L, FAILED_AT), failedRefund(8L, 200L, FAILED_AT)));
    willThrow(new IllegalStateException("serialize failed"))
        .given(eventReplayPublisher)
        .republish(
            argThat(e -> e instanceof RefundFailedEvent rf && rf.bookingId() == 100L), anyString());

    // when
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);

    // then: 앞 건에서 끊기면 뒤의 미해결 건이 영영 복구되지 않는다.
    verify(eventReplayPublisher, times(2)).republish(any(), anyString());
    // 실패한 건을 건너뛰고 그 뒤 건으로 커서가 전진한다 = 실패분은 다음 랩에서 다시 시도된다.
    assertThat(meterRegistry.get(MetricNames.PAYMENT_REFUND_SIGNAL_REPLAYED).counter().count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("성공: 실제로 나간 건수를 재발행 카운터에 반영한다")
  void counts_replayed_signals() throws Exception {
    // given
    given(refundRepository.countByStatus(RefundStatus.FAILED)).willReturn(2L);
    givenTargets(0L, List.of(failedRefund(7L, 100L, FAILED_AT), failedRefund(8L, 200L, FAILED_AT)));

    // when
    paymentReplayRefundFailureSignalUseCase.execute(BATCH_SIZE);

    // then
    assertThat(meterRegistry.get(MetricNames.PAYMENT_REFUND_SIGNAL_REPLAYED).counter().count())
        .isEqualTo(2.0);
  }

  @Test
  @DisplayName("성공: 한 건도 못 내보낸 채 연속 실패하면 주기를 중단하고 커서를 옮기지 않는다")
  void stops_cycle_when_nothing_sent_and_failures_repeat() throws Exception {
    // given: 브로커 전면 장애면 남은 건도 같은 이유로 실패하는데, send()는 건당 브로커 메타데이터
    // 대기(max.block.ms)를 문다. 끝까지 밀면 스케줄러 스레드가 주기보다 오래 잡힌다.
    List<Refund> targets = new ArrayList<>();
    for (long id = 1; id <= 5; id++) {
      targets.add(failedRefund(id, 100L + id, FAILED_AT));
    }
    given(refundRepository.countByStatus(RefundStatus.FAILED)).willReturn(5L);
    givenTargets(0L, targets);
    willThrow(new IllegalStateException("broker down"))
        .given(eventReplayPublisher)
        .republish(any(), anyString());

    // when
    paymentReplayRefundFailureSignalUseCase.execute(5);
    paymentReplayRefundFailureSignalUseCase.execute(5);

    // then: 3건에서 접으므로 주기당 5건이 아니라 3건만 시도한다.
    verify(eventReplayPublisher, times(6)).republish(any(), anyString());
    // 커서를 옮기지 않았다 — 두 주기 모두 0부터 조회한다.
    verify(refundRepository, times(2))
        .findByStatusAndIdGreaterThanOrderByIdAsc(
            eq(RefundStatus.FAILED), eq(0L), any(Pageable.class));
  }

  @Test
  @DisplayName("성공: 한 건이라도 나갔으면 이후 연속 실패에도 주기를 접지 않는다")
  void does_not_abort_after_first_success() throws Exception {
    // given: 위치 무관으로 접으면 랩 선두의 특정 건이 계속 실패할 때 뒤쪽 전체가 무기한 대기한다 —
    // 이 스케줄러가 없애려던 봉쇄가 위치만 바꿔 재현된다. 한 건이라도 나갔으면 브로커는 살아 있다.
    List<Refund> targets = new ArrayList<>();
    for (long id = 1; id <= 5; id++) {
      targets.add(failedRefund(id, 100L + id, FAILED_AT));
    }
    given(refundRepository.countByStatus(RefundStatus.FAILED)).willReturn(5L);
    givenTargets(0L, targets);
    // 첫 건만 성공하고 나머지 4건은 전부 실패한다(호출 순서로 고정한다).
    willDoNothing()
        .willThrow(new IllegalStateException("per-row failure"))
        .given(eventReplayPublisher)
        .republish(any(), anyString());

    // when
    paymentReplayRefundFailureSignalUseCase.execute(5);

    // then: 중단 없이 5건을 모두 시도하고, 커서는 성공한 첫 건에 머문다.
    verify(eventReplayPublisher, times(5)).republish(any(), anyString());
    assertThat(meterRegistry.get(MetricNames.PAYMENT_REFUND_SIGNAL_REPLAYED).counter().count())
        .isEqualTo(1.0);
  }
}
