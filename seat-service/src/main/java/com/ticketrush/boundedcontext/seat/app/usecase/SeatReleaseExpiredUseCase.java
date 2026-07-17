package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.global.config.SeatReleaseProperties;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 만료 HOLD 좌석을 AVAILABLE로 롤백하는 fallback 유스케이스(오케스트레이터).
 *
 * <p>대량 만료 시 단일 트랜잭션에 UPDATE/afterCommit이 몰리지 않도록, 만료 좌석을 {@code chunkSize}건 단위로 나눠 {@link
 * SeatReleaseExpiredChunkProcessor}에 위임한다. 각 청크가 독립 트랜잭션으로 커밋되도록 <b>이 메서드에는 {@code @Transactional}을
 * 두지 않는다</b>(트랜잭션이 있으면 REQUIRED 전파로 청크 호출이 외부 트랜잭션에 합류해 단일 트랜잭션이 되어버림).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReleaseExpiredUseCase {

  private final SeatReleaseExpiredChunkProcessor chunkProcessor;
  private final SeatReleaseProperties seatReleaseProperties;

  public void execute() {
    LocalDateTime now = LocalDateTime.now();
    int chunkSize = seatReleaseProperties.getChunkSize();
    int maxChunks = seatReleaseProperties.getMaxChunks();

    int totalReleased = 0;
    int processedChunks = 0;
    boolean capReached = false;

    while (processedChunks < maxChunks) {
      SeatReleaseExpiredChunkProcessor.ChunkResult result =
          chunkProcessor.releaseChunk(now, chunkSize);
      totalReleased += result.released();
      processedChunks++;

      // 다음 청크가 남았는지는 '조회된' 건수로 판단한다. 실제 해제 건수(released)로 판단하면, 조회 이후 결제가
      // 확정돼 건너뛴 좌석 때문에 아직 만료 좌석이 남았는데도 루프가 조기 종료된다.
      if (result.fetched() < chunkSize) {
        break;
      }
      // 마지막 청크까지 가득 찬 채로 maxChunks에 도달 = 처리 상한 소진(잔여 좌석 존재 여부는 미확인).
      if (processedChunks == maxChunks) {
        capReached = true;
      }
    }

    if (capReached) {
      log.warn(
          "만료 좌석 처리 상한(chunkSize={} x maxChunks={})에 도달해 이번 tick 처리를 중단했습니다."
              + " 잔여 만료 좌석이 있으면 다음 스케줄 tick에서 처리됩니다.",
          chunkSize,
          maxChunks);
    }
    if (totalReleased > 0) {
      log.info("만료된 좌석 {}개의 상태를 AVAILABLE로 롤백했습니다. 기준 시간: {}", totalReleased, now);
    }
  }
}
