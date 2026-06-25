package com.ticketrush.boundedcontext.booking.in.eventlistener;

import static org.assertj.core.api.Assertions.assertThatCode;
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
import org.springframework.kafka.support.Acknowledgment;

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
  @DisplayName("좌석 SOLD 확정 호출이 실패해도 예외를 전파하지 않고 오프셋을 커밋한다")
  void handlePaymentConfirmedSwallowsSoldFailure() {
    // given
    Long bookingId = 10L;
    LocalDateTime paidAt = LocalDateTime.of(2026, 5, 22, 10, 30);
    DomainEventEnvelope envelope = envelope();
    PaymentConfirmedEvent event =
        new PaymentConfirmedEvent(1L, bookingId, SEAT_ID, 4L, 50000L, paidAt);

    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event);
    given(bookingConfirmUseCase.execute(bookingId, paidAt, SEAT_ID)).willReturn(BOOKING_NUMBER);
    willThrow(new RuntimeException("seat-service 호출 실패"))
        .given(seatRestClient)
        .confirmSold(BOOKING_NUMBER, SEAT_ID);

    // when & then: 좌석 SOLD 호출 실패가 리스너 밖으로 전파되지 않는다
    assertThatCode(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .doesNotThrowAnyException();

    // then: 예매 확정과 SOLD 호출은 시도되었고, 실패와 무관하게 오프셋은 커밋된다
    verify(bookingConfirmUseCase).execute(bookingId, paidAt, SEAT_ID);
    verify(seatRestClient).confirmSold(BOOKING_NUMBER, SEAT_ID);
    verify(acknowledgment).acknowledge();
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
