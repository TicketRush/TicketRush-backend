package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.support.SeatHoldExpiredPublisher;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReleaseExpiredChunkProcessor {

  private final SeatRepository seatRepository;
  private final SeatStatusEventPublisher seatStatusEventPublisher;
  private final SeatHoldExpiredPublisher seatHoldExpiredPublisher;

  /**
   * 청크 처리 결과.
   *
   * @param fetched 조회된 만료 좌석 수. 오케스트레이터가 <b>다음 청크가 남았는지</b> 판단하는 값이다.
   * @param released 실제로 해제된 좌석 수. 아래 {@code releaseChunk}에서 건너뛴 좌석은 빠지므로 로그·지표는 이 값을 쓴다.
   */
  public record ChunkResult(int fetched, int released) {}

  @Transactional
  public ChunkResult releaseChunk(LocalDateTime now, int chunkSize) {
    List<Seat> expiredSeats =
        seatRepository.findExpiredHoldSeats(SeatStatus.HOLD, now, PageRequest.of(0, chunkSize));

    int released = 0;
    for (Seat seat : expiredSeats) {
      // 조회 이후 결제가 확정됐거나(SOLD) 해제 후 다른 예매로 재선점됐을 수 있다(ABA). 가드가 달린 조건부
      // UPDATE만이 팔린 좌석과 남의 살아있는 선점을 건드리지 않는다.
      int updated =
          seatRepository.releaseExpiredHoldById(
              seat.getId(), seat.getBookingNumber(), now, SeatStatus.HOLD, SeatStatus.AVAILABLE);
      if (updated == 0) {
        log.info("좌석 {}은(는) 조회 이후 선점 상태가 바뀌어 해제를 건너뜁니다.", seat.getId());
        continue;
      }

      seatHoldExpiredPublisher.publish(seat);
      seat.releaseHold();
      seatStatusEventPublisher.publishAfterCommit(seat);
      released++;
    }

    return new ChunkResult(expiredSeats.size(), released);
  }
}
