package com.ticketrush.boundedcontext.payment.in.eventlistener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.app.facade.PaymentFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.booking.event.BookingExpiredEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class BookingExpiredEventListenerTest {

  @InjectMocks private BookingExpiredEventListener listener;

  @Mock private PaymentFacade paymentFacade;

  @Mock private JsonConverter jsonConverter;

  @Mock private Acknowledgment acknowledgment;

  @Test
  @DisplayName("예매 만료 이벤트를 수신하면 만료 booking 등록을 요청하고 오프셋을 커밋한다")
  void handleBookingExpired() {
    // given
    Long bookingId = 100L;
    LocalDateTime expiredAt = LocalDateTime.of(2026, 6, 21, 10, 0);
    String payload = "{\"bookingId\":100,\"expiredAt\":\"2026-06-21T10:00:00\"}";
    DomainEventEnvelope envelope =
        new DomainEventEnvelope(
            "event-id",
            BookingExpiredEvent.EVENT_NAME,
            Instant.now(),
            BookingExpiredEvent.TOPIC,
            payload,
            null);
    BookingExpiredEvent event = new BookingExpiredEvent(bookingId, expiredAt);

    given(jsonConverter.deserialize(payload, BookingExpiredEvent.class)).willReturn(event);

    // when
    listener.handleBookingExpired(envelope, acknowledgment);

    // then
    verify(paymentFacade).registerExpiredBooking(bookingId, expiredAt);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("처리 중 예외가 발생하면 오프셋을 커밋하지 않고 예외를 전파한다")
  void handleBookingExpired_propagates_on_error() {
    // given
    String payload = "broken";
    DomainEventEnvelope envelope =
        new DomainEventEnvelope(
            "event-id",
            BookingExpiredEvent.EVENT_NAME,
            Instant.now(),
            BookingExpiredEvent.TOPIC,
            payload,
            null);
    given(jsonConverter.deserialize(payload, BookingExpiredEvent.class))
        .willThrow(new RuntimeException("deserialize 실패"));

    // when & then
    assertThatThrownBy(() -> listener.handleBookingExpired(envelope, acknowledgment))
        .isInstanceOf(RuntimeException.class);

    verify(paymentFacade, never()).registerExpiredBooking(anyLong(), any());
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("이미 등록된 만료 booking(unique 제약 위반)은 중복으로 간주하고 정상 ack 한다")
  void handleBookingExpired_acks_on_duplicate() {
    // given
    Long bookingId = 100L;
    LocalDateTime expiredAt = LocalDateTime.of(2026, 6, 21, 10, 0);
    String payload = "{\"bookingId\":100,\"expiredAt\":\"2026-06-21T10:00:00\"}";
    DomainEventEnvelope envelope =
        new DomainEventEnvelope(
            "event-id",
            BookingExpiredEvent.EVENT_NAME,
            Instant.now(),
            BookingExpiredEvent.TOPIC,
            payload,
            null);
    BookingExpiredEvent event = new BookingExpiredEvent(bookingId, expiredAt);

    given(jsonConverter.deserialize(payload, BookingExpiredEvent.class)).willReturn(event);
    willThrow(new DataIntegrityViolationException("duplicate bookingId"))
        .given(paymentFacade)
        .registerExpiredBooking(bookingId, expiredAt);

    // when & then
    assertThatCode(() -> listener.handleBookingExpired(envelope, acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }
}
