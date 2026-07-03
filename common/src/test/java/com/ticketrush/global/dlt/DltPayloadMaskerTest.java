package com.ticketrush.global.dlt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DltPayloadMaskerTest {

  @Test
  @DisplayName("이메일은 로컬파트 첫 글자만 남기고 마스킹한다")
  void masks_email() {
    // given
    String input = "{\"email\":\"user@example.com\"}";

    // when
    String masked = DltPayloadMasker.mask(input);

    // then
    assertThat(masked).doesNotContain("user@example.com");
    assertThat(masked).contains("u***@***");
  }

  @Test
  @DisplayName("구분자로 나뉜 카드번호는 마지막 4자리만 남기고 마스킹한다")
  void masks_card_number() {
    // given
    String input = "카드 4111-1111-1111-1234 결제";

    // when
    String masked = DltPayloadMasker.mask(input);

    // then
    assertThat(masked).doesNotContain("4111-1111-1111-1234");
    assertThat(masked).contains("****-****-****-1234");
  }

  @Test
  @DisplayName("한국 휴대폰 번호는 가운데를 마스킹한다")
  void masks_phone_number() {
    // given
    String input = "연락처 010-1234-5678 입니다";

    // when
    String masked = DltPayloadMasker.mask(input);

    // then
    assertThat(masked).doesNotContain("010-1234-5678");
    assertThat(masked).contains("010-****-5678");
  }

  @Test
  @DisplayName("PII가 없는 정상 JSON은 오탐 없이 원형을 유지한다")
  void keeps_non_pii_payload_intact() {
    // given: 짧은 식별자 숫자열을 카드/전화로 오탐하지 않아야 한다.
    String input = "{\"bookingId\":100,\"userId\":5,\"amount\":25000}";

    // when
    String masked = DltPayloadMasker.mask(input);

    // then
    assertThat(masked).isEqualTo(input);
  }

  @Test
  @DisplayName("주민등록번호는 뒷자리 6개를 마스킹하고 성별자리만 남긴다")
  void masks_resident_registration_number() {
    // given
    String input = "주민번호 900101-1234567 입니다";

    // when
    String masked = DltPayloadMasker.mask(input);

    // then
    assertThat(masked).doesNotContain("900101-1234567");
    assertThat(masked).contains("900101-1******");
  }

  @Test
  @DisplayName("주민번호 패턴([1-4] 성별자리)에 해당하지 않는 6자리-7자리 숫자열은 오탐하지 않는다")
  void does_not_false_positive_on_non_rrn_number_sequences() {
    // given: 성별자리가 [1-4] 범위 밖(예: 5로 시작하는 뒷자리)이라 RRN으로 매칭되지 않아야 한다.
    String input = "{\"refId\":\"123456-5678901\",\"bookingId\":100}";

    // when
    String masked = DltPayloadMasker.mask(input);

    // then
    assertThat(masked).isEqualTo(input);
  }

  @Test
  @DisplayName("null 입력은 null을 반환한다")
  void returns_null_for_null_input() {
    assertThat(DltPayloadMasker.mask(null)).isNull();
  }
}
