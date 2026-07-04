package com.ticketrush.boundedcontext.booking.in.eventlistener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.booking.app.usecase.BookingConfirmUseCase;
import com.ticketrush.boundedcontext.booking.out.apiclient.SeatRestClient;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.json.DeserializationException;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.payment.event.PaymentConfirmedEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

@ExtendWith(MockitoExtension.class)
class PaymentConfirmedEventListenerTest {

  @InjectMocks private PaymentConfirmedEventListener listener;

  @Mock private BookingConfirmUseCase bookingConfirmUseCase;

  @Mock private SeatRestClient seatRestClient;

  @Mock private JsonConverter jsonConverter;

  @Mock private Acknowledgment acknowledgment;

  // jsonConverter를 모킹하므로 payload 문자열은 실제로 파싱되지 않는다(역직렬화 결과는 스텁으로 지정).
  // 따라서 payload는 의미 없는 식별 문자열로 둔다.
  private static final String PAYLOAD = "payload";
  private static final Long SEAT_ID = 3L;
  private static final String BOOKING_NUMBER = "BOOK-1234";

  private DomainEventEnvelope envelope() {
    return new DomainEventEnvelope(
        "event-id",
        PaymentConfirmedEvent.EVENT_NAME,
        Instant.now(),
        PaymentConfirmedEvent.TOPIC,
        PAYLOAD,
        null);
  }

  @Test
  @DisplayName("결제 완료 이벤트를 수신하면 예매를 확정하고 좌석 SOLD를 확정한 뒤 오프셋을 커밋한다")
  void handlePaymentConfirmed() {
    // given
    Long bookingId = 10L;
    LocalDateTime paidAt = LocalDateTime.of(2026, 5, 22, 10, 30);
    DomainEventEnvelope envelope = envelope();
    PaymentConfirmedEvent event =
        new PaymentConfirmedEvent(1L, bookingId, SEAT_ID, 4L, 50000L, paidAt);

    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event);
    given(bookingConfirmUseCase.execute(bookingId, paidAt, SEAT_ID)).willReturn(BOOKING_NUMBER);

    // when
    listener.handlePaymentConfirmed(envelope, acknowledgment);

    // then
    verify(bookingConfirmUseCase).execute(bookingId, paidAt, SEAT_ID);
    verify(seatRestClient).confirmSold(BOOKING_NUMBER, SEAT_ID);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("seatId 검증 실패 등으로 확정 유스케이스가 예외를 던지면 좌석 SOLD를 호출하지 않고 오프셋을 커밋한다")
  void handlePaymentConfirmedSkipsSoldWhenConfirmFails() {
    // given
    Long bookingId = 10L;
    LocalDateTime paidAt = LocalDateTime.of(2026, 5, 22, 10, 30);
    DomainEventEnvelope envelope = envelope();
    PaymentConfirmedEvent event =
        new PaymentConfirmedEvent(1L, bookingId, SEAT_ID, 4L, 50000L, paidAt);

    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event);
    willThrow(new BusinessException(ErrorStatus.BOOKING_SEAT_MISMATCH))
        .given(bookingConfirmUseCase)
        .execute(bookingId, paidAt, SEAT_ID);

    // when & then: 예외를 리스너 밖으로 전파하지 않는다(파티션 블로킹 방지)
    assertThatCode(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .doesNotThrowAnyException();

    // then: 검증 실패 시 좌석 SOLD 확정은 호출되지 않고, 오프셋은 커밋된다
    verify(bookingConfirmUseCase).execute(bookingId, paidAt, SEAT_ID);
    verify(seatRestClient, never()).confirmSold(any(), anyLong());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("좌석 SOLD 확정이 4xx(예: 409 중복)로 실패하면 재시도하지 않고 오프셋을 커밋한다")
  void handlePaymentConfirmedAcksWhenSoldReturns4xx() {
    // given: 이미 SOLD된 좌석에 대한 409 등 4xx는 결정적 응답이라 재시도해도 결과가 같다
    Long bookingId = 10L;
    LocalDateTime paidAt = LocalDateTime.of(2026, 5, 22, 10, 30);
    DomainEventEnvelope envelope = envelope();
    PaymentConfirmedEvent event =
        new PaymentConfirmedEvent(1L, bookingId, SEAT_ID, 4L, 50000L, paidAt);

    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event);
    given(bookingConfirmUseCase.execute(bookingId, paidAt, SEAT_ID)).willReturn(BOOKING_NUMBER);
    willThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", null, null, null))
        .given(seatRestClient)
        .confirmSold(BOOKING_NUMBER, SEAT_ID);

    // when & then: 4xx는 삼키고 예매 확정을 유지하며 커밋한다
    assertThatCode(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .doesNotThrowAnyException();

    // then
    verify(bookingConfirmUseCase).execute(bookingId, paidAt, SEAT_ID);
    verify(seatRestClient).confirmSold(BOOKING_NUMBER, SEAT_ID);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("좌석 SOLD 확정이 409가 아닌 4xx(예: 404)로 실패해도 결정적 응답이라 재시도하지 않고 오프셋을 커밋한다")
  void handlePaymentConfirmedAcksWhenSoldReturnsNon409_4xx() {
    // given: 404 등 409가 아닌 4xx도 결정적이라 재시도 무의미 → CRITICAL 로그 후 커밋
    Long bookingId = 10L;
    LocalDateTime paidAt = LocalDateTime.of(2026, 5, 22, 10, 30);
    DomainEventEnvelope envelope = envelope();
    PaymentConfirmedEvent event =
        new PaymentConfirmedEvent(1L, bookingId, SEAT_ID, 4L, 50000L, paidAt);

    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event);
    given(bookingConfirmUseCase.execute(bookingId, paidAt, SEAT_ID)).willReturn(BOOKING_NUMBER);
    willThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null))
        .given(seatRestClient)
        .confirmSold(BOOKING_NUMBER, SEAT_ID);

    // when & then
    assertThatCode(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("좌석 SOLD 확정이 5xx(일시 인프라 오류)로 실패하면 예외를 전파하고 오프셋을 커밋하지 않는다(재시도→DLT 위임)")
  void handlePaymentConfirmedRethrowsWhenSoldReturns5xx() {
    // given: 5xx는 일시 오류라 재시도하면 성공할 수 있다
    Long bookingId = 10L;
    LocalDateTime paidAt = LocalDateTime.of(2026, 5, 22, 10, 30);
    DomainEventEnvelope envelope = envelope();
    PaymentConfirmedEvent event =
        new PaymentConfirmedEvent(1L, bookingId, SEAT_ID, 4L, 50000L, paidAt);

    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event);
    given(bookingConfirmUseCase.execute(bookingId, paidAt, SEAT_ID)).willReturn(BOOKING_NUMBER);
    willThrow(
            HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "error", null, null, null))
        .given(seatRestClient)
        .confirmSold(BOOKING_NUMBER, SEAT_ID);

    // when & then: 5xx는 리스너 밖으로 전파되어 컨테이너 재시도→DLT 파이프라인을 태운다
    assertThatThrownBy(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .isInstanceOf(HttpServerErrorException.class);

    // then: 오프셋은 커밋되지 않는다
    verify(seatRestClient).confirmSold(BOOKING_NUMBER, SEAT_ID);
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("확정 유스케이스가 일시적(인프라) 예외를 던지면 예외를 전파하고 오프셋을 커밋하지 않는다")
  void handlePaymentConfirmedRethrowsTransientConfirmFailure() {
    // given: 일반 RuntimeException은 일시 실패로 분류된다
    Long bookingId = 10L;
    LocalDateTime paidAt = LocalDateTime.of(2026, 5, 22, 10, 30);
    DomainEventEnvelope envelope = envelope();
    PaymentConfirmedEvent event =
        new PaymentConfirmedEvent(1L, bookingId, SEAT_ID, 4L, 50000L, paidAt);

    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event);
    willThrow(new RuntimeException("DB 일시 장애"))
        .given(bookingConfirmUseCase)
        .execute(bookingId, paidAt, SEAT_ID);

    // when & then
    assertThatThrownBy(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .isInstanceOf(RuntimeException.class);

    // then: SOLD는 호출되지 않고 오프셋도 커밋되지 않는다
    verify(seatRestClient, never()).confirmSold(any(), anyLong());
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("이벤트 역직렬화에 실패해도 예외를 전파하지 않고 오프셋을 커밋한다")
  void handlePaymentConfirmedSwallowsDeserializationException() {
    // given: 실제 JsonConverter가 변환 실패 시 던지는 예외 타입으로 스텁한다
    DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class))
        .willThrow(new DeserializationException(new RuntimeException("broken payload")));

    // when & then: 예외를 리스너 밖으로 전파하지 않는다
    assertThatCode(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .doesNotThrowAnyException();

    // then: 확정 로직과 좌석 SOLD 확정은 호출되지 않고, 오프셋은 커밋된다
    verify(bookingConfirmUseCase, never()).execute(anyLong(), any(), anyLong());
    verify(seatRestClient, never()).confirmSold(any(), anyLong());
    verify(acknowledgment).acknowledge();
  }
}
