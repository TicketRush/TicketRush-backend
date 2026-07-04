package com.ticketrush.global.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.json.DeserializationException;
import com.ticketrush.global.status.ErrorStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

class KafkaConsumerErrorPolicyTest {

  @Nested
  @DisplayName("isPermanent: 재시도해도 결과가 바뀌지 않는 영구 실패 판정")
  class IsPermanent {

    @Test
    @DisplayName("BusinessException은 영구 실패다")
    void businessExceptionIsPermanent() {
      BusinessException e = new BusinessException(ErrorStatus.SEAT_NOT_AVAILABLE);

      assertThat(KafkaConsumerErrorPolicy.isPermanent(e)).isTrue();
    }

    @Test
    @DisplayName("cause 체인 깊은 곳에 BusinessException이 있어도 영구 실패로 판정한다")
    void nestedBusinessExceptionIsPermanent() {
      Throwable e =
          new RuntimeException(
              "wrapper",
              new IllegalStateException(new BusinessException(ErrorStatus.BOOKING_EXPIRED)));

      assertThat(KafkaConsumerErrorPolicy.isPermanent(e)).isTrue();
    }

    @Test
    @DisplayName("payload 역직렬화 실패(DeserializationException)는 BusinessException 하위라 영구 실패다")
    void deserializationExceptionIsPermanent() {
      DeserializationException e = new DeserializationException(new RuntimeException("broken"));

      assertThat(KafkaConsumerErrorPolicy.isPermanent(e)).isTrue();
    }

    @Test
    @DisplayName("일반 RuntimeException(인프라 오류 대용)은 일시 실패다")
    void plainRuntimeExceptionIsTransient() {
      RuntimeException e = new RuntimeException("DB 일시 장애");

      assertThat(KafkaConsumerErrorPolicy.isPermanent(e)).isFalse();
    }

    @Test
    @DisplayName("크로스서비스 HTTP 4xx는 BusinessException이 아니라 일시로 분류된다(SOLD 오분류 리스크 고정)")
    void httpClientErrorExceptionIsNotPermanent() {
      HttpClientErrorException e =
          HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", null, null, null);

      // 이 헬퍼로는 HTTP 예외를 영구로 구분할 수 없다 → 크로스서비스 호출은 상태코드 기반으로 별도 처리해야 한다.
      assertThat(KafkaConsumerErrorPolicy.isPermanent(e)).isFalse();
    }

    @Test
    @DisplayName("null이면 일시로 간주한다")
    void nullIsTransient() {
      assertThat(KafkaConsumerErrorPolicy.isPermanent(null)).isFalse();
    }
  }

  @Nested
  @DisplayName("isExpectedConflict: 재수신 등으로 자연 발생하는 상태충돌(409) 판정")
  class IsExpectedConflict {

    @Test
    @DisplayName("화이트리스트에 등록된 멱등 상태충돌(예: SEAT_NOT_AVAILABLE)은 예상된 상태충돌이다")
    void whitelistedConflictIsExpectedConflict() {
      BusinessException e = new BusinessException(ErrorStatus.SEAT_NOT_AVAILABLE);

      assertThat(KafkaConsumerErrorPolicy.isExpectedConflict(e)).isTrue();
    }

    @Test
    @DisplayName("화이트리스트에 없는 409 정합성 위반(예: BOOKING_SEAT_MISMATCH)은 예상된 상태충돌이 아니다(CRITICAL 대상)")
    void non409NotWhitelistedConflictIsNotExpectedConflict() {
      BusinessException mismatch = new BusinessException(ErrorStatus.BOOKING_SEAT_MISMATCH);

      assertThat(KafkaConsumerErrorPolicy.isExpectedConflict(mismatch)).isFalse();
    }

    @Test
    @DisplayName("409가 아닌 BusinessException(예: 500 역직렬화)은 예상된 상태충돌이 아니다")
    void non409BusinessExceptionIsNotExpectedConflict() {
      DeserializationException e = new DeserializationException(new RuntimeException("broken"));

      assertThat(KafkaConsumerErrorPolicy.isExpectedConflict(e)).isFalse();
    }

    @Test
    @DisplayName("BusinessException이 아니면 예상된 상태충돌이 아니다")
    void nonBusinessExceptionIsNotExpectedConflict() {
      HttpServerErrorException e =
          HttpServerErrorException.create(
              HttpStatus.INTERNAL_SERVER_ERROR, "error", null, null, null);

      assertThat(KafkaConsumerErrorPolicy.isExpectedConflict(e)).isFalse();
    }
  }
}
