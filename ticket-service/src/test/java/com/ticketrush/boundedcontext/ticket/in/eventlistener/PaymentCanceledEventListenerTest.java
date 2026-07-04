package com.ticketrush.boundedcontext.ticket.in.eventlistener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.ticket.app.usecase.TicketCancelUseCase;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.json.DeserializationException;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.payment.event.PaymentCanceledEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.BDDMockito;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class PaymentCanceledEventListenerTest {

  @InjectMocks private PaymentCanceledEventListener listener;

  @Mock private TicketCancelUseCase ticketCancelUseCase;

  @Mock private JsonConverter jsonConverter;

  @Mock private Acknowledgment acknowledgment;

  // jsonConverter를 모킹하므로 payload 문자열은 실제로 파싱되지 않는다(역직렬화 결과는 스텁으로 지정).
  private static final String PAYLOAD = "payload";
  private static final Long BOOKING_ID = 10L;

  private DomainEventEnvelope envelope() {
    return new DomainEventEnvelope(
        "event-id",
        PaymentCanceledEvent.EVENT_NAME,
        Instant.now(),
        PaymentCanceledEvent.TOPIC,
        PAYLOAD,
        null);
  }

  private PaymentCanceledEvent event() {
    return new PaymentCanceledEvent(
        1L, BOOKING_ID, 3L, 4L, 50000L, "단순 변심", LocalDateTime.of(2026, 5, 22, 10, 30));
  }

  @Test
  @DisplayName("결제 취소 이벤트를 수신하면 해당 bookingId로 입장권을 취소하고 오프셋을 커밋한다")
  void handlePaymentCanceled() {
    // given
    DomainEventEnvelope envelope = envelope();
    BDDMockito.given(jsonConverter.deserialize(PAYLOAD, PaymentCanceledEvent.class))
        .willReturn(event());

    // when
    listener.handlePaymentCanceled(envelope, acknowledgment);

    // then
    verify(ticketCancelUseCase).execute(BOOKING_ID);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("일시적(인프라) 예외가 발생하면 예외를 전파하고 오프셋을 커밋하지 않는다(재시도→DLT 위임)")
  void handlePaymentCanceledRethrowsTransientFailure() {
    // given: 일반 RuntimeException은 일시 실패로 분류된다
    DomainEventEnvelope envelope = envelope();
    BDDMockito.given(jsonConverter.deserialize(PAYLOAD, PaymentCanceledEvent.class))
        .willReturn(event());
    willThrow(new RuntimeException("DB 일시 장애")).given(ticketCancelUseCase).execute(BOOKING_ID);

    // when & then: 일시 실패는 리스너 밖으로 전파되어 컨테이너 재시도→DLT 파이프라인을 태운다
    assertThatThrownBy(() -> listener.handlePaymentCanceled(envelope, acknowledgment))
        .isInstanceOf(RuntimeException.class);

    // then: 취소는 시도되었으나 오프셋은 커밋되지 않는다
    verify(ticketCancelUseCase).execute(BOOKING_ID);
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("영구(비즈니스) 예외가 발생하면 예외를 전파하지 않고 오프셋을 커밋한다")
  void handlePaymentCanceledAcksPermanentFailure() {
    // given: BusinessException은 영구 실패로 분류된다(재시도 무의미)
    DomainEventEnvelope envelope = envelope();
    BDDMockito.given(jsonConverter.deserialize(PAYLOAD, PaymentCanceledEvent.class))
        .willReturn(event());
    willThrow(new BusinessException(ErrorStatus.TICKET_NOT_FOUND))
        .given(ticketCancelUseCase)
        .execute(BOOKING_ID);

    // when & then: 영구 실패는 로그 후 커밋되어 파티션 블로킹을 막는다
    assertThatCode(() -> listener.handlePaymentCanceled(envelope, acknowledgment))
        .doesNotThrowAnyException();

    // then
    verify(ticketCancelUseCase).execute(BOOKING_ID);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("이벤트 역직렬화에 실패해도 예외를 전파하지 않고 오프셋을 커밋한다")
  void handlePaymentCanceledSwallowsDeserializationException() {
    // given: 실제 JsonConverter가 변환 실패 시 던지는 예외 타입으로 스텁한다
    DomainEventEnvelope envelope = envelope();
    BDDMockito.given(jsonConverter.deserialize(PAYLOAD, PaymentCanceledEvent.class))
        .willThrow(new DeserializationException(new RuntimeException("broken payload")));

    // when & then: 예외를 리스너 밖으로 전파하지 않는다
    assertThatCode(() -> listener.handlePaymentCanceled(envelope, acknowledgment))
        .doesNotThrowAnyException();

    // then: 취소 유스케이스는 호출되지 않고, 오프셋은 커밋된다
    verify(ticketCancelUseCase, never()).execute(anyLong());
    verify(acknowledgment).acknowledge();
  }
}
