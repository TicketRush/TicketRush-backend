package com.ticketrush.boundedcontext.seat.app.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.seat.app.mapper.SeatMapper;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.types.SeatStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class SeatStatusEventPublisherTest {

  private SeatStatusEventPublisher seatStatusEventPublisher;
  private SimpleMeterRegistry meterRegistry;

  @Mock private SeatStatusEventSender seatStatusEventSender;

  private final SeatMapper seatMapper = Mappers.getMapper(SeatMapper.class);

  @BeforeEach
  void setUp() {
    // @InjectMocks 대신 손으로 조립한다 — 카운터를 생성자에서 등록하므로 실제 레지스트리가 필요하다
    // (SeatHoldUseCaseTest와 같은 패턴).
    meterRegistry = new SimpleMeterRegistry();
    seatStatusEventPublisher =
        new SeatStatusEventPublisher(seatStatusEventSender, seatMapper, meterRegistry);
  }

  @Test
  @DisplayName("트랜잭션이 없으면 좌석 상태 변경 이벤트를 즉시 발행한다")
  void publishAfterCommitPublishesImmediatelyWithoutTransaction() {
    // given
    LocalDateTime holdExpiredAt = LocalDateTime.now().plusMinutes(5);
    Seat seat = seat(holdExpiredAt);

    // when
    seatStatusEventPublisher.publishAfterCommit(seat, SeatEventSource.BOOKING_HOLD);

    // then
    verify(seatStatusEventSender)
        .send(
            argThat(
                event ->
                    event.performanceId().equals(1L)
                        && event.seatLayoutId().equals(10L)
                        && event.seatNumber().equals("A-1")
                        && event.seatStatus() == SeatStatus.HOLD
                        && event.holdExpiredAt().equals(holdExpiredAt)));
  }

  @ParameterizedTest
  @EnumSource(SeatEventSource.class)
  @DisplayName("발행 경로마다 자기 source 태그로만 계측된다")
  void publishAfterCommitCountsPerSource(SeatEventSource source) {
    // when
    seatStatusEventPublisher.publishAfterCommit(seat(null), source);

    // then — 자기 태그만 1이고 나머지 경로는 0으로 남는다. 한 경로의 증가가 다른 경로에 새면
    // #403 §10이 남긴 질문(도착 불균일의 출처)에 다시 답하지 못하게 된다.
    for (SeatEventSource other : SeatEventSource.values()) {
      assertThat(publishedCount(other)).isEqualTo(other == source ? 1.0 : 0.0);
    }
  }

  @Test
  @DisplayName("카운터는 기동 시 전부 등록되어, 발행이 없는 경로도 0으로 노출된다")
  void countersAreRegisteredEagerlyForEverySource() {
    // then — 지연 생성이면 '조용한 경로'와 '계측되지 않는 경로'가 그래프에서 구분되지 않는다.
    for (SeatEventSource source : SeatEventSource.values()) {
      assertThat(
              meterRegistry
                  .find(MetricNames.SEAT_SSE_EVENT_PUBLISHED)
                  .tag(MetricNames.TAG_SOURCE, source.tagValue())
                  .counter())
          .as("경로 %s 의 카운터가 등록되어 있어야 한다", source)
          .isNotNull();
    }
  }

  @Test
  @DisplayName("트랜잭션이 있으면 커밋 전에는 발행도 계측도 하지 않는다")
  void publishAfterCommitDefersUntilCommit() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      // when
      seatStatusEventPublisher.publishAfterCommit(seat(null), SeatEventSource.SCHEDULER_FALLBACK);

      // then — 롤백된 트랜잭션의 발행을 세면 큐 도착과 어긋난다. 아직 커밋 전이므로 둘 다 일어나지 않아야 한다.
      verifyNoInteractions(seatStatusEventSender);
      assertThat(publishedCount(SeatEventSource.SCHEDULER_FALLBACK)).isZero();
      assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

      // when — 커밋 훅이 돌면 그때 발행되고 그때 세어진다
      TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

      // then
      verify(seatStatusEventSender).send(argThat(event -> event.performanceId().equals(1L)));
      assertThat(publishedCount(SeatEventSource.SCHEDULER_FALLBACK)).isEqualTo(1.0);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private double publishedCount(SeatEventSource source) {
    return meterRegistry
        .counter(MetricNames.SEAT_SSE_EVENT_PUBLISHED, MetricNames.TAG_SOURCE, source.tagValue())
        .count();
  }

  private Seat seat(LocalDateTime holdExpiredAt) {
    return Seat.builder()
        .seatLayoutId(10L)
        .performanceId(1L)
        .seatNumber("A-1")
        .seatStatus(SeatStatus.HOLD)
        .holdExpiredAt(holdExpiredAt)
        .build();
  }
}
