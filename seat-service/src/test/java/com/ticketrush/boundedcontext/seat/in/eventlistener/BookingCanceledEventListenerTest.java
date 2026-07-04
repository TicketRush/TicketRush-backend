package com.ticketrush.boundedcontext.seat.in.eventlistener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.booking.event.BookingCanceledEvent;
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
class BookingCanceledEventListenerTest {

  @InjectMocks private BookingCanceledEventListener listener;

  @Mock private SeatFacade seatFacade;

  @Mock private JsonConverter jsonConverter;

  @Mock private Acknowledgment acknowledgment;

  private static final String PAYLOAD = "payload";
  private static final Long SEAT_ID = 3L;
  private static final String BOOKING_NUMBER = "BOOK-1234";

  private DomainEventEnvelope envelope() {
    return new DomainEventEnvelope(
        "event-id",
        BookingCanceledEvent.EVENT_NAME,
        Instant.now(),
        BookingCanceledEvent.TOPIC,
        PAYLOAD,
        null);
  }

  private BookingCanceledEvent event() {
    return new BookingCanceledEvent(
        10L, BOOKING_NUMBER, SEAT_ID, 4L, LocalDateTime.of(2026, 5, 22, 10, 30));
  }

  @Test
  @DisplayName("예매 취소 이벤트를 수신하면 좌석을 반환하고 오프셋을 커밋한다")
  void handleBookingCanceled() {
    // given
    DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCanceledEvent.class)).willReturn(event());

    // when
    listener.handleBookingCanceled(envelope, acknowledgment);

    // then
    verify(seatFacade).releaseBookedSeat(SEAT_ID, BOOKING_NUMBER);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("일시적(인프라) 예외가 발생하면 예외를 전파하고 오프셋을 커밋하지 않는다(재시도→DLT 위임)")
  void handleBookingCanceledRethrowsTransientFailure() {
    // given
    DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCanceledEvent.class)).willReturn(event());
    willThrow(new RuntimeException("DB 일시 장애"))
        .given(seatFacade)
        .releaseBookedSeat(SEAT_ID, BOOKING_NUMBER);

    // when & then
    assertThatThrownBy(() -> listener.handleBookingCanceled(envelope, acknowledgment))
        .isInstanceOf(RuntimeException.class);

    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("영구(비즈니스) 예외가 발생하면 예외를 전파하지 않고 오프셋을 커밋한다")
  void handleBookingCanceledAcksPermanentFailure() {
    // given
    DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCanceledEvent.class)).willReturn(event());
    willThrow(new BusinessException(ErrorStatus.SEAT_NOT_FOUND))
        .given(seatFacade)
        .releaseBookedSeat(SEAT_ID, BOOKING_NUMBER);

    // when & then
    assertThatCode(() -> listener.handleBookingCanceled(envelope, acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }
}
