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
 *
 * <p><b>루프 안의 {@code em.clear()}가 아웃박스 INSERT를 버리지 않는 것은 ID 전략에 달려 있다.</b> 조건부 UPDATE에 붙은
 * {@code @Modifying(clearAutomatically = true)}는 반복마다 영속성 컨텍스트를 비우는데, 직전 반복이 발행한 아웃박스 엔티티가 그 안에 보류
 * 중이면 함께 버려진다. 지금 안전한 이유는 {@code OutboxEntity}가 {@code GenerationType.IDENTITY}라 {@code save()} 시점에
 * INSERT가 <b>즉시 실행</b>되기 때문이다(이미 나간 INSERT는 clear가 되돌리지 못한다). 벌크 JPQL의 query space는 {@code seat}
 * 테이블뿐이라 auto-flush도 아웃박스를 밀어주지 않는다. <b>ID 전략이 sequence/TABLE로 바뀌면 만료 이벤트가 조용히 유실된다</b>(에러 로그도 남지
 * 않는다).
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
      // 조회 이후 결제가 확정됐거나(SOLD) 해제 후 다른 예매로 재선점됐을 수 있다(ABA). 조회 스냅샷의 만료
      // 시각을 가드로 넘겨 "내가 본 그 선점 그대로일 때만" 해제한다.
      int updated =
          seatRepository.releaseExpiredHoldById(
              seat.getId(), seat.getHoldExpiredAt(), SeatStatus.HOLD, SeatStatus.AVAILABLE);
      if (updated == 0) {
        // 레이스를 정확히 막은 정상 동작이고 러시 중엔 청크마다 다발로 찍힌다. 건수는 ChunkResult의
        // fetched - released로 드러나므로 개별 건은 debug로 둔다.
        log.debug("좌석 {}은(는) 조회 이후 선점 상태가 바뀌어 해제를 건너뜁니다.", seat.getId());
        continue;
      }

      /* 아래 seat는 detach 상태다. @Modifying(clearAutomatically = true)가 UPDATE 직후 영속성 컨텍스트를
       * 비우기 때문이다. 그래서 seat.releaseHold()는 DB에 나가지 않는 순수 in-memory 조정이고(위 조건부
       * UPDATE가 이미 DB를 바꿨다), publish에는 조회 스냅샷의 bookingNumber가 실린다(DB는 이미 null).
       * 가드가 통과했다는 것이 곧 그 스냅샷이 최신이라는 뜻이므로 페이로드는 정확하다.
       *
       * clearAutomatically를 끄면 releaseHold()가 관리 상태 엔티티를 건드려 가드 없는
       * UPDATE ... WHERE seat_id = ? 더티 체킹 쓰기가 되살아난다. 즉 위 가드가 무력화된다. */
      seatHoldExpiredPublisher.publish(seat);
      seat.releaseHold();
      seatStatusEventPublisher.publishAfterCommit(seat);
      released++;
    }

    return new ChunkResult(expiredSeats.size(), released);
  }
}
