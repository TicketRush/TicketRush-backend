package com.ticketrush.boundedcontext.seat.in.eventlistener;

import com.ticketrush.boundedcontext.seat.app.usecase.SeatReleaseSingleUseCase;
import com.ticketrush.boundedcontext.seat.domain.constant.SeatLockKey;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SeatLockExpirationListener extends KeyExpirationEventMessageListener {

  private final SeatReleaseSingleUseCase seatReleaseSingleUseCase;

  public SeatLockExpirationListener(
      RedisMessageListenerContainer listenerContainer,
      SeatReleaseSingleUseCase seatReleaseSingleUseCase) {
    super(listenerContainer);
    this.seatReleaseSingleUseCase = seatReleaseSingleUseCase;
  }

  @Override
  public void onMessage(Message message, byte[] pattern) {
    String expiredKey = new String(message.getBody(), StandardCharsets.UTF_8);

    // 만료된 키가 좌석 락(seat:lock:)인지 확인
    if (expiredKey.startsWith(SeatLockKey.PREFIX)) {
      try {
        // "seat:lock:123" 형태에서 "123" 추출
        String seatIdStr = expiredKey.replace(SeatLockKey.PREFIX, "");
        Long seatId = Long.valueOf(seatIdStr);

        log.debug("Redis 락 만료 감지됨. seatId: {}", seatId);

        // 즉각적인 상태 롤백 실행
        seatReleaseSingleUseCase.execute(seatId);

      } catch (NumberFormatException e) {
        log.error("만료된 Redis 키에서 좌석 ID를 파싱할 수 없습니다. key: {}", expiredKey, e);
      } catch (Exception e) {
        log.error("Redis 만료 이벤트 처리 중 오류 발생. seatId 파싱키: {}", expiredKey, e);
      }
    }
  }
}
