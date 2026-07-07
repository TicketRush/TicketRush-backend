package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료 HOLD 좌석 해제를 청크 단위 트랜잭션으로 처리한다.
 *
 * <p>{@link SeatReleaseExpiredUseCase}(오케스트레이터)와 별도 빈으로 분리한 이유: 오케스트레이터의 반복 호출이 Spring 프록시를 거쳐
 * <b>청크마다 새 트랜잭션</b>을 열도록 하기 위함이다. 같은 클래스의 메서드로 두면 self-invocation으로 프록시를 우회해 단일 트랜잭션이 되어버린다. 청크마다
 * 커밋되므로 {@code afterCommit} SSE 이벤트도 청크 단위로 발화되고, 트랜잭션 범위는 {@code chunkSize}로 제한된다.
 */
@Service
@RequiredArgsConstructor
public class SeatReleaseExpiredChunkProcessor {

  private final SeatRepository seatRepository;
  private final SeatStatusEventPublisher seatStatusEventPublisher;

  @Transactional
  public int releaseChunk(LocalDateTime now, int chunkSize) {
    List<Seat> expiredSeats =
        seatRepository.findExpiredHoldSeats(SeatStatus.HOLD, now, PageRequest.of(0, chunkSize));

    for (Seat seat : expiredSeats) {
      seat.releaseHold();
      seatStatusEventPublisher.publishAfterCommit(seat);
    }

    return expiredSeats.size();
  }
}
