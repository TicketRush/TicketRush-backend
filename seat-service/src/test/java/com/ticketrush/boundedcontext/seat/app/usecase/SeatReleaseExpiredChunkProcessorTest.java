package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import java.util.List;
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

    // when
    int released = seatReleaseExpiredChunkProcessor.releaseChunk(now, chunkSize);

    // then
    assertThat(released).isEqualTo(2);
    assertThat(expiredSeat1.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    assertThat(expiredSeat2.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    verify(seatRepository).findExpiredHoldSeats(SeatStatus.HOLD, now, PageRequest.of(0, chunkSize));
    verify(seatStatusEventPublisher).publishAfterCommit(expiredSeat1);
    verify(seatStatusEventPublisher).publishAfterCommit(expiredSeat2);
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
