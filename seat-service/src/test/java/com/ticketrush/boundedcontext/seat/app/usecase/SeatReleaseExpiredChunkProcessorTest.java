package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.seat.app.support.SeatHoldExpiredPublisher;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class SeatReleaseExpiredChunkProcessorTest {

  @Mock private SeatRepository seatRepository;
  @Mock private SeatStatusEventPublisher seatStatusEventPublisher;
  @Mock private SeatHoldExpiredPublisher seatHoldExpiredPublisher;

  @InjectMocks private SeatReleaseExpiredChunkProcessor seatReleaseExpiredChunkProcessor;

  @Test
  @DisplayName("청크 크기만큼 만료 좌석을 조회해 AVAILABLE로 롤백하고 좌석별 이벤트를 발행한 뒤 처리 건수를 반환한다")
  void releaseChunk_ReleasesAndPublishesPerSeat() {
    // given
    int chunkSize = 100;
    LocalDateTime now = LocalDateTime.now();
    Seat expiredSeat1 = buildHoldSeat("A-1", now.minusMinutes(1));
    Seat expiredSeat2 = buildHoldSeat("A-2", now.minusMinutes(2));
    given(
            seatRepository.findExpiredHoldSeats(
                eq(SeatStatus.HOLD), any(LocalDateTime.class), any(Pageable.class)))
        .willReturn(List.of(expiredSeat1, expiredSeat2));
    given(
            seatRepository.releaseExpiredHoldById(
                any(), any(LocalDateTime.class), eq(SeatStatus.HOLD), eq(SeatStatus.AVAILABLE)))
        .willReturn(1);

    // when
    SeatReleaseExpiredChunkProcessor.ChunkResult result =
        seatReleaseExpiredChunkProcessor.releaseChunk(now, chunkSize);

    // then
    assertThat(result.fetched()).isEqualTo(2);
    assertThat(result.released()).isEqualTo(2);
    assertThat(expiredSeat1.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    assertThat(expiredSeat2.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    verify(seatRepository).findExpiredHoldSeats(SeatStatus.HOLD, now, PageRequest.of(0, chunkSize));
    verify(seatStatusEventPublisher).publishAfterCommit(expiredSeat1);
    verify(seatStatusEventPublisher).publishAfterCommit(expiredSeat2);
    verify(seatHoldExpiredPublisher).publish(expiredSeat1);
    verify(seatHoldExpiredPublisher).publish(expiredSeat2);
  }

  @Test
  @DisplayName("hold 만료 이벤트 발행은 releaseHold() 전에 일어나 bookingNumber가 살아있는 상태로 캡처된다")
  void releaseChunk_PublishesBeforeReleaseHold() {
    // given
    LocalDateTime now = LocalDateTime.now();
    Seat seat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(now.minusMinutes(1))
            .bookingNumber("BK-1")
            .build();
    given(
            seatRepository.findExpiredHoldSeats(
                eq(SeatStatus.HOLD), any(LocalDateTime.class), any(Pageable.class)))
        .willReturn(List.of(seat));
    given(
            seatRepository.releaseExpiredHoldById(
                any(), any(LocalDateTime.class), eq(SeatStatus.HOLD), eq(SeatStatus.AVAILABLE)))
        .willReturn(1);

    AtomicReference<String> bookingNumberAtPublish = new AtomicReference<>();
    willAnswer(
            invocation -> {
              bookingNumberAtPublish.set(invocation.getArgument(0, Seat.class).getBookingNumber());
              return null;
            })
        .given(seatHoldExpiredPublisher)
        .publish(seat);

    // when
    seatReleaseExpiredChunkProcessor.releaseChunk(now, 100);

    // then: 발행 시점엔 bookingNumber가 아직 null이 아니어야 한다(releaseHold가 나중에 클리어)
    assertThat(bookingNumberAtPublish.get()).isEqualTo("BK-1");
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
  }

  @Test
  @DisplayName("조회 이후 결제가 확정돼 HOLD가 아니게 된 좌석은 해제하지 않고 이벤트도 발행하지 않는다")
  void releaseChunk_SkipsSeatConfirmedAfterFetch() {
    // given: 조건부 UPDATE가 0건 -> 조회와 갱신 사이에 confirmSoldById가 좌석을 SOLD로 만든 상황
    LocalDateTime now = LocalDateTime.now();
    Seat seat = buildHoldSeat("A-1", now.minusMinutes(1));
    given(
            seatRepository.findExpiredHoldSeats(
                eq(SeatStatus.HOLD), any(LocalDateTime.class), any(Pageable.class)))
        .willReturn(List.of(seat));
    given(
            seatRepository.releaseExpiredHoldById(
                any(), any(LocalDateTime.class), eq(SeatStatus.HOLD), eq(SeatStatus.AVAILABLE)))
        .willReturn(0);

    // when
    SeatReleaseExpiredChunkProcessor.ChunkResult result =
        seatReleaseExpiredChunkProcessor.releaseChunk(now, 100);

    // then: 팔린 좌석이 풀려 재판매되면 안 된다
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.HOLD);
    verify(seatHoldExpiredPublisher, never()).publish(any(Seat.class));
    verify(seatStatusEventPublisher, never()).publishAfterCommit(any(Seat.class));
    // 조회 건수는 그대로 세되(오케스트레이터의 다음 청크 판단용), 해제 건수에선 빠져야 로그가 부풀지 않는다
    assertThat(result.fetched()).isEqualTo(1);
    assertThat(result.released()).isZero();
  }

  private Seat buildHoldSeat(String seatNumber, LocalDateTime holdExpiredAt) {
    return Seat.builder()
        .seatLayoutId(1L)
        .performanceId(1L)
        .seatNumber(seatNumber)
        .seatStatus(SeatStatus.HOLD)
        .holdExpiredAt(holdExpiredAt)
        .build();
  }
}
