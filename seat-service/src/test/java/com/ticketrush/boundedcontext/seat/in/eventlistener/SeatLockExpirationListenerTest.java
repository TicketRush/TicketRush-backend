package com.ticketrush.boundedcontext.seat.in.eventlistener;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.seat.app.usecase.SeatReleaseSingleUseCase;
import com.ticketrush.boundedcontext.seat.domain.constant.SeatLockKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@ExtendWith(MockitoExtension.class)
class SeatLockExpirationListenerTest {

  @Mock private RedisMessageListenerContainer listenerContainer;

  @Mock private SeatReleaseSingleUseCase seatReleaseSingleUseCase;

  @InjectMocks private SeatLockExpirationListener seatLockExpirationListener;

  @Test
  @DisplayName("올바른 키(seat:lock:) 만료 이벤트 수신 시 useCase를 호출한다")
  void onMessage_WithValidKey_CallsUseCase() {
    // given
    String expiredKey = SeatLockKey.of(123L);
    Message message = new DefaultMessage(new byte[0], expiredKey.getBytes());

    // when
    seatLockExpirationListener.onMessage(message, null);

    // then
    verify(seatReleaseSingleUseCase).execute(123L);
  }

  @Test
  @DisplayName("다른 접두사의 키 만료 이벤트 수신 시 useCase를 호출하지 않는다")
  void onMessage_WithInvalidPrefix_DoesNotCallUseCase() {
    // given
    String expiredKey = "some:other:key:123";
    Message message = new DefaultMessage(new byte[0], expiredKey.getBytes());

    // when
    seatLockExpirationListener.onMessage(message, null);

    // then
    verify(seatReleaseSingleUseCase, never()).execute(anyLong());
  }
}
