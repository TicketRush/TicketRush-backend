package com.ticketrush.boundedcontext.booking.in.eventlistener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.booking.app.usecase.BookingConfirmUseCase;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.exception.BusinessException;
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

  @Mock private JsonConverter jsonConverter;

  @Mock private Acknowledgment acknowledgment;

  private DomainEventEnvelope envelope(String payload) {
    return new DomainEventEnvelope(
        "event-id",
        PaymentConfirmedEvent.EVENT_NAME,
        Instant.now(),
        PaymentConfirmedEvent.TOPIC,
        payload,
        null);
  }

  @Test
  @DisplayName("결제 완료 이벤트를 수신하면 예매를 확정하고 오프셋을 커밋한다")
  void handlePaymentConfirmed() {
    // given
    Long bookingId = 10L;
    LocalDateTime paidAt = LocalDateTime.of(2026, 5, 22, 10, 30);
    String payload = "{\"booking_id\":10}";
    DomainEventEnvelope envelope = envelope(payload);
    PaymentConfirmedEvent event = new PaymentConfirmedEvent(1L, bookingId, 3L, 4L, 50000L, paidAt);

    given(jsonConverter.deserialize(payload, PaymentConfirmedEvent.class)).willReturn(event);

    // when
    listener.handlePaymentConfirmed(envelope, acknowledgment);

    // then
    verify(bookingConfirmUseCase).execute(bookingId, paidAt);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("확정 불가능한 예매로 유스케이스가 예외를 던져도 전파하지 않고 오프셋을 커밋한다")
  void handlePaymentConfirmedSwallowsUseCaseException() {
    // given
    Long bookingId = 10L;
    LocalDateTime paidAt = LocalDateTime.of(2026, 5, 22, 10, 30);
    String payload = "{\"booking_id\":10}";
    DomainEventEnvelope envelope = envelope(payload);
    PaymentConfirmedEvent event = new PaymentConfirmedEvent(1L, bookingId, 3L, 4L, 50000L, paidAt);

    given(jsonConverter.deserialize(payload, PaymentConfirmedEvent.class)).willReturn(event);
    willThrow(new BusinessException(ErrorStatus.BOOKING_EXPIRED))
        .given(bookingConfirmUseCase)
        .execute(bookingId, paidAt);

    // when
    listener.handlePaymentConfirmed(envelope, acknowledgment);

    // then
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("이벤트 역직렬화에 실패해도 예외를 전파하지 않고 오프셋을 커밋한다")
  void handlePaymentConfirmedSwallowsDeserializationException() {
    // given
    String payload = "broken-payload";
    DomainEventEnvelope envelope = envelope(payload);

    given(jsonConverter.deserialize(payload, PaymentConfirmedEvent.class))
        .willThrow(new IllegalArgumentException("deserialize failed"));

    // when
    listener.handlePaymentConfirmed(envelope, acknowledgment);

    // then
    verify(bookingConfirmUseCase, never()).execute(anyLong(), any());
    verify(acknowledgment).acknowledge();
  }
}
