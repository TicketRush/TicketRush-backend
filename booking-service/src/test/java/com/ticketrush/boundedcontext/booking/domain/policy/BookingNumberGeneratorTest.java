package com.ticketrush.boundedcontext.booking.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BookingNumberGeneratorTest {

  private BookingNumberGenerator generator;

  @BeforeEach
  void setUp() {
    generator = new BookingNumberGenerator();
  }

  @Test
  @DisplayName("1,000번의 예약 번호 생성 시, 모두 오입력 방지 문자가 제외된 11자리 포맷이어야 한다.")
  void testBookingNumberFormatInBulk() {
    // given
    int testCount = 1_000;

    // when & then
    for (int i = 0; i < testCount; i++) {
      String bookingNumber = generator.generate();

      // 중복 검증 제거, 정규식 하나로 길이, 하이픈 위치, 허용 문자 모두 검증
      assertThat(bookingNumber).matches("^[2-9A-HJ-NP-Z]{5}-[2-9A-HJ-NP-Z]{5}$");
    }
  }

  @Test
  @DisplayName("10,000번의 예약 번호 생성 시, 중복되는 번호가 발생하지 않아야 한다.")
  void testBookingNumberRandomnessInBulk() {
    // given
    int testCount = 10_000;
    Set<String> generatedNumbers = new HashSet<>();

    // when
    for (int i = 0; i < testCount; i++) {
      generatedNumbers.add(generator.generate());
    }

    // then
    // Set의 특성상 중복이 발생했다면 크기가 testCount보다 작아짐
    assertThat(generatedNumbers).hasSize(testCount);
  }
}
