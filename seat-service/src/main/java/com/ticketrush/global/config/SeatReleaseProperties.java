package com.ticketrush.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 만료 HOLD 좌석 해제(fallback 스케줄러) 청크 처리 설정.
 *
 * <p>대량 만료 시 단일 트랜잭션에 UPDATE/afterCommit이 몰리는 것을 막기 위해, {@code SeatReleaseExpiredUseCase}는 만료 좌석을
 * {@link #chunkSize}건 단위로 나눠 각 청크를 독립 트랜잭션으로 처리한다. 한 번의 스케줄 tick에서 최대 {@link #chunkSize} × {@link
 * #maxChunks}건까지 처리하며, 초과분은 다음 tick 또는 Redis 실시간 리스너가 처리한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.seat.release")
public class SeatReleaseProperties {

  private int chunkSize = 100; // 청크 1건당 처리(조회/UPDATE)할 좌석 수 = 트랜잭션 범위
  private int maxChunks = 20; // tick당 최대 청크 수 (처리 상한 = chunkSize x maxChunks)
}
