package com.ticketrush.boundedcontext.payment.domain.types;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundTriggerTest {

  private final Locale originalLocale = Locale.getDefault();

  @AfterEach
  void restoreLocale() {
    Locale.setDefault(originalLocale);
  }

  @Test
  @DisplayName("성공: 메트릭 태그 값은 기본 로케일이 바뀌어도 달라지지 않는다")
  void tag_is_locale_independent() {
    // given — tr_TR 은 대문자 I 를 dotless ı 로 내린다
    Locale.setDefault(Locale.forLanguageTag("tr-TR"));

    // when & then — 로케일에 따라 갈리면 같은 사건이 두 시계열로 쪼개진다
    assertThat(RefundTrigger.CONFIRM_SIGNAL_LOST.tag()).isEqualTo("confirm_signal_lost");
    assertThat(RefundTrigger.SEAT_CONFIRM_FAILED.tag()).isEqualTo("seat_confirm_failed");
    assertThat(RefundTrigger.USER_CANCEL.tag()).isEqualTo("user_cancel");
  }

  @Test
  @DisplayName("성공: 모든 트리거가 서로 다른 PG 취소 사유를 갖는다")
  void reasons_are_distinct() {
    // 사유는 PG 관리자 화면과 정산 분석에서 사고 보상 건을 사용자 취소와 구분하는 축이다.
    // 두 트리거가 같은 문자열을 쓰면 그 구분이 PG 쪽에서 사라진다.
    assertThat(
            Arrays.stream(RefundTrigger.values()).map(RefundTrigger::getReason).distinct().count())
        .isEqualTo(RefundTrigger.values().length);
  }
}
