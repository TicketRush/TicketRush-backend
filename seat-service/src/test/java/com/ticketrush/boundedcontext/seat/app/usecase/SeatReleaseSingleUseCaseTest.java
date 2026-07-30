package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.seat.app.support.SeatEventSource;
import com.ticketrush.boundedcontext.seat.app.support.SeatHoldExpiredPublisher;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatReleaseSingleUseCaseTest {

  @Mock private SeatRepository seatRepository;
  @Mock private SeatStatusEventPublisher seatStatusEventPublisher;
  @Mock private SeatHoldExpiredPublisher seatHoldExpiredPublisher;

  @InjectMocks private SeatReleaseSingleUseCase seatReleaseSingleUseCase;

  @Test
  @DisplayName("존재하는 좌석의 만료 이벤트 수신 시 상태를 AVAILABLE로 롤백하고 hold 만료 이벤트를 발행한다")
  void execute_WhenSeatExists_ReleasesHold() {
    // given
    Long seatId = 1L;
    Seat seat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(LocalDateTime.now().minusMinutes(1))
            .bookingNumber("BK-1")
            .build();
    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));
    given(
            seatRepository.releaseExpiredHoldById(
                eq(seatId),
                any(LocalDateTime.class),
                eq(SeatStatus.HOLD),
                eq(SeatStatus.AVAILABLE)))
        .willReturn(1);

    // hold 만료 이벤트 발행은 releaseHold() '전에' 일어나야 bookingNumber가 살아있다 → 발행 시점 값을 캡처
    AtomicReference<String> bookingNumberAtPublish = new AtomicReference<>();
    willAnswer(
            invocation -> {
              bookingNumberAtPublish.set(invocation.getArgument(0, Seat.class).getBookingNumber());
              return null;
            })
        .given(seatHoldExpiredPublisher)
        .publish(seat);

    // when
    seatReleaseSingleUseCase.execute(seatId);

    // then
    verify(seatRepository).findById(seatId);
    verify(seatHoldExpiredPublisher).publish(seat);
    verify(seatStatusEventPublisher).publishAfterCommit(seat, SeatEventSource.EXPIRE_SINGLE);
    assertThat(bookingNumberAtPublish.get()).isEqualTo("BK-1"); // releaseHold 전 캡처 보장
  }

  @Test
  @DisplayName("HOLD 상태가 아닌 좌석의 만료 이벤트 수신 시 어떤 이벤트도 발행하지 않는다")
  void execute_WhenSeatIsNotHold_DoesNotPublishEvent() {
    // given
    Long seatId = 1L;
    Seat seat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.SOLD)
            .build();
    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));

    // when
    seatReleaseSingleUseCase.execute(seatId);

    // then
    verify(seatRepository).findById(seatId);
    verifyNoInteractions(seatStatusEventPublisher);
    verifyNoInteractions(seatHoldExpiredPublisher);
  }

  @Test
  @DisplayName("조회 이후 결제가 확정된 좌석은 해제하지 않고 이벤트도 발행하지 않는다")
  void execute_WhenSeatConfirmedAfterFetch_SkipsRelease() {
    // given: 조회 시점엔 HOLD였지만 조건부 UPDATE가 0건 -> 그 사이 confirmSoldById가 SOLD로 확정한 상황
    Long seatId = 1L;
    Seat seat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(LocalDateTime.now().minusMinutes(1))
            .bookingNumber("BK-1")
            .build();
    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));
    given(
            seatRepository.releaseExpiredHoldById(
                eq(seatId),
                any(LocalDateTime.class),
                eq(SeatStatus.HOLD),
                eq(SeatStatus.AVAILABLE)))
        .willReturn(0);

    // when
    seatReleaseSingleUseCase.execute(seatId);

    // then: 팔린 좌석이 풀려 재판매되면 안 된다
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.HOLD);
    verifyNoInteractions(seatHoldExpiredPublisher);
    verifyNoInteractions(seatStatusEventPublisher);
  }

  @Test
  @DisplayName("존재하지 않는 좌석의 만료 이벤트 수신 시 예외를 던지지 않고 경고 로그만 남긴다")
  void execute_WhenSeatDoesNotExist_DoesNotThrow() {
    // given
    Long seatId = 999L;
    given(seatRepository.findById(seatId)).willReturn(Optional.empty());

    // when
    seatReleaseSingleUseCase.execute(seatId);

    // then
    verify(seatRepository).findById(seatId);
    verify(mock(Seat.class), never()).releaseHold();
    verifyNoInteractions(seatHoldExpiredPublisher);
  }
}
