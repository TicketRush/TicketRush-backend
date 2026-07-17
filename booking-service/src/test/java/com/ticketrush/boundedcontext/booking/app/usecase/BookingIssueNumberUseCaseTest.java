package com.ticketrush.boundedcontext.booking.app.usecase;

import static com.ticketrush.global.status.ErrorStatus.BOOKING_NUMBER_RETRY_EXCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketrush.boundedcontext.booking.domain.policy.BookingNumberGenerator;
import com.ticketrush.global.exception.BusinessException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class BookingIssueNumberUseCaseTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;
  @Mock private BookingNumberGenerator bookingNumberGenerator;

  @InjectMocks private BookingIssueNumberUseCase bookingIssueNumberUseCase;

  @Test
  @DisplayName("Redis 통신이 정상적일 때 고유 예약 번호를 성공적으로 발급한다. (Mocking)")
  void issueNumberSuccessWithMock() {
    // given
    String expectedNumber = "A2C4E-789JK";
    when(bookingNumberGenerator.generate()).thenReturn(expectedNumber);

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(anyString(), eq("RESERVED"), any(Duration.class)))
        .thenReturn(true);

    // when
    String bookingNumber = bookingIssueNumberUseCase.execute();

    // then
    assertThat(bookingNumber).isNotNull();
    assertThat(bookingNumber).isEqualTo(expectedNumber);
  }

  @Test
  @DisplayName("계속해서 중복이 발생하면 최대 5번 재시도 후 예외를 발생시킨다. (Mocking)")
  void issueNumberFailAfterMaxRetriesWithMock() {
    // given: Redis의 setIfAbsent가 항상 false(중복)를 반환한다고 가정
    when(bookingNumberGenerator.generate()).thenReturn("A2C4E-789JK");
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(anyString(), eq("RESERVED"), any(Duration.class)))
        .thenReturn(false);

    // when & then
    assertThatThrownBy(() -> bookingIssueNumberUseCase.execute())
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(BOOKING_NUMBER_RETRY_EXCEEDED);

    verify(bookingNumberGenerator, times(5)).generate();
    verify(valueOperations, times(5)).setIfAbsent(anyString(), eq("RESERVED"), any(Duration.class));
  }
}
