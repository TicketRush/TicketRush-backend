package com.ticketrush.boundedcontext.seat.app.support;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.seat.app.mapper.SeatMapper;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatStatusEventPublisherTest {

  @InjectMocks private SeatStatusEventPublisher seatStatusEventPublisher;

  @Mock private SeatStatusEventSender seatStatusEventSender;

  @Spy private SeatMapper seatMapper = Mappers.getMapper(SeatMapper.class);

  @Test
  @DisplayName("트랜잭션이 없으면 좌석 상태 변경 이벤트를 즉시 발행한다")
  void publishAfterCommitPublishesImmediatelyWithoutTransaction() {
    // given
    LocalDateTime holdExpiredAt = LocalDateTime.now().plusMinutes(5);
    Seat seat =
        Seat.builder()
            .seatLayoutId(10L)
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(holdExpiredAt)
            .build();

    // when
    seatStatusEventPublisher.publishAfterCommit(seat);

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
}
