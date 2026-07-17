package com.ticketrush.boundedcontext.booking.domain.policy;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class BookingNumberGenerator {

  // 오입력 방지: 숫자 0, 1 및 영문 O, I, L 제외 (총 31개 문자)
  private static final String ALLOWED_CHARACTERS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
  private static final int NUMBER_LENGTH = 10;
  private static final SecureRandom random = new SecureRandom();

  /**
   * 정책에 맞는 예약 번호를 생성합니다.
   *
   * @return XXXXX-XXXXX 형식의 예약 번호
   */
  public String generate() { // static 제거
    StringBuilder sb = new StringBuilder(NUMBER_LENGTH);

    for (int i = 0; i < NUMBER_LENGTH; i++) {
      int randomIndex = random.nextInt(ALLOWED_CHARACTERS.length());
      sb.append(ALLOWED_CHARACTERS.charAt(randomIndex));
    }

    // 5자리씩 끊어서 하이픈(-) 추가
    return sb.substring(0, 5) + "-" + sb.substring(5, 10);
  }
}
