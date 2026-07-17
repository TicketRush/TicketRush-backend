package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.policy.BookingNumberGenerator;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingIssueNumberUseCase {

  private final StringRedisTemplate redisTemplate;
  private final BookingNumberGenerator bookingNumberGenerator;

  private static final String REDIS_KEY_PREFIX = "booking:number:";
  private static final int MAX_RETRY_COUNT = 5; // 중복 발생 시 최대 재시도 횟수

  public String execute() {
    for (int i = 0; i < MAX_RETRY_COUNT; i++) {
      String generatedNumber = bookingNumberGenerator.generate();
      String redisKey = REDIS_KEY_PREFIX + generatedNumber;

      /* Redis SETNX를 이용한 원자적 중복 체크
       예약 번호가 DB에 저장될 때까지만 임시로 잡아두면 되므로 TTL을 짧게(10분) 설정
      */
      Boolean isUnique =
          redisTemplate.opsForValue().setIfAbsent(redisKey, "RESERVED", Duration.ofMinutes(10));

      if (Boolean.TRUE.equals(isUnique)) {
        return generatedNumber;
      }

      log.warn("예약 번호 충돌 발생, 재시도합니다. (시도 횟수: {})", i + 1);
    }

    throw new BusinessException(ErrorStatus.BOOKING_NUMBER_RETRY_EXCEEDED);
  }
}
