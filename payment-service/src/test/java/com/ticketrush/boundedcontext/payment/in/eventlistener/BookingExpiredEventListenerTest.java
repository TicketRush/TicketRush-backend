package com.ticketrush.boundedcontext.payment.in.eventlistener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.app.facade.PaymentFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxService;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
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

  @Mock private InboxService inboxService;

  @Mock private Acknowledgment acknowledgment;

  private static final Long BOOKING_ID = 100L;
  private static final LocalDateTime EXPIRED_AT = LocalDateTime.of(2026, 6, 21, 10, 0);
  private static final String PAYLOAD = "{\"bookingId\":100,\"expiredAt\":\"2026-06-21T10:00:00\"}";

  private DomainEventEnvelope envelope(String payload) {
    return new DomainEventEnvelope(
        "event-id",
        BookingExpiredEvent.EVENT_NAME,
        Instant.now(),
        BookingExpiredEvent.TOPIC,
        payload,
        null);
  }

  private BookingExpiredEvent event() {
    return new BookingExpiredEvent(BOOKING_ID, EXPIRED_AT);
  }

  /** Inbox가 최초 수신으로 판정해 비즈니스 콜백을 실행하고 true를 반환하도록 스텁한다. */
  private void givenInboxProcessesFirst() {
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.PAYMENT),
                any(DomainEventEnvelope.class),
                any(Runnable.class)))
        .willAnswer(
            invocation -> {
              invocation.getArgument(2, Runnable.class).run();
              return true;
            });
  }

  @Test
  @DisplayName("최초 수신이면 만료 booking 등록을 요청하고 오프셋을 커밋한다")
  void handleBookingExpired() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, BookingExpiredEvent.class)).willReturn(event());
    givenInboxProcessesFirst();

    // when
    listener.handleBookingExpired(envelope(PAYLOAD), acknowledgment);

    // then
    verify(paymentFacade).registerExpiredBooking(BOOKING_ID, EXPIRED_AT);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("이미 처리된 이벤트(inbox 중복)면 등록을 실행하지 않고 오프셋만 커밋한다")
  void handleBookingExpired_skips_duplicate() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, BookingExpiredEvent.class)).willReturn(event());
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.PAYMENT),
                any(DomainEventEnvelope.class),
                any(Runnable.class)))
        .willReturn(false);

    // when
    listener.handleBookingExpired(envelope(PAYLOAD), acknowledgment);

    // then
    verify(paymentFacade, never()).registerExpiredBooking(anyLong(), any());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("처리 중 역직렬화 예외(일시)가 발생하면 오프셋을 커밋하지 않고 예외를 전파한다")
  void handleBookingExpired_propagates_on_error() {
    // given: 역직렬화는 inbox 이전 단계라 inbox 스텁이 필요 없다
    given(jsonConverter.deserialize("broken", BookingExpiredEvent.class))
        .willThrow(new RuntimeException("deserialize 실패"));

    // when & then
    assertThatThrownBy(() -> listener.handleBookingExpired(envelope("broken"), acknowledgment))
        .isInstanceOf(RuntimeException.class);

    verify(paymentFacade, never()).registerExpiredBooking(anyLong(), any());
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("이미 등록된 만료 booking(unique 위반, DIVE)은 중복으로 간주하고 정상 ack 한다")
  void handleBookingExpired_acks_on_duplicate() {
    // given: 등록 시 bookingId unique 위반이 발생(동시 중복)
    given(jsonConverter.deserialize(PAYLOAD, BookingExpiredEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new DataIntegrityViolationException("duplicate bookingId"))
        .given(paymentFacade)
        .registerExpiredBooking(BOOKING_ID, EXPIRED_AT);

    // when & then
    assertThatCode(() -> listener.handleBookingExpired(envelope(PAYLOAD), acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("동시 중복 수신으로 inbox unique가 경합하면(DuplicateEventException) 예외를 전파하지 않고 오프셋을 커밋한다")
  void handleBookingExpired_acks_on_inbox_race() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, BookingExpiredEvent.class)).willReturn(event());
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.PAYMENT),
                any(DomainEventEnvelope.class),
                any(Runnable.class)))
        .willThrow(
            new DuplicateEventException(
                KafkaConsumerGroup.PAYMENT,
                "event-id",
                new DataIntegrityViolationException("duplicate")));

    // when & then
    assertThatCode(() -> listener.handleBookingExpired(envelope(PAYLOAD), acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("영구(비즈니스) 예외가 발생하면 예외를 전파하지 않고 오프셋을 커밋한다")
  void handleBookingExpired_acks_on_permanent_failure() {
    // given: BusinessException은 영구 실패로 분류되어 커밋된다(재시도 무의미)
    given(jsonConverter.deserialize(PAYLOAD, BookingExpiredEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new BusinessException(ErrorStatus.PAYMENT_NOT_FOUND))
        .given(paymentFacade)
        .registerExpiredBooking(BOOKING_ID, EXPIRED_AT);

    // when & then
    assertThatCode(() -> listener.handleBookingExpired(envelope(PAYLOAD), acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }
}
