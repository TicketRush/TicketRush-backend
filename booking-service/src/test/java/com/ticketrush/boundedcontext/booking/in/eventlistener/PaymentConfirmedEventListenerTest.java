package com.ticketrush.boundedcontext.booking.in.eventlistener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.booking.app.usecase.BookingConfirmUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetBookingNumberUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingPublishSeatConfirmFailedUseCase;
import com.ticketrush.boundedcontext.booking.out.apiclient.SeatRestClient;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxService;
import com.ticketrush.global.json.DeserializationException;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.payment.event.PaymentConfirmedEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

@ExtendWith(MockitoExtension.class)
class PaymentConfirmedEventListenerTest {

  @InjectMocks private PaymentConfirmedEventListener listener;

  @Mock private BookingConfirmUseCase bookingConfirmUseCase;

  @Mock private BookingGetBookingNumberUseCase bookingGetBookingNumberUseCase;

  @Mock private BookingPublishSeatConfirmFailedUseCase bookingPublishSeatConfirmFailedUseCase;

  @Mock private SeatRestClient seatRestClient;

  @Mock private JsonConverter jsonConverter;

  @Mock private InboxService inboxService;

  @Mock private Acknowledgment acknowledgment;

  // jsonConverter를 모킹하므로 payload 문자열은 실제로 파싱되지 않는다(역직렬화 결과는 스텁으로 지정).
  // 따라서 payload는 의미 없는 식별 문자열로 둔다.
  private static final String PAYLOAD = "payload";
  private static final Long BOOKING_ID = 10L;
  private static final Long SEAT_ID = 3L;
  private static final String BOOKING_NUMBER = "BOOK-1234";
  private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 5, 22, 10, 30);

  private DomainEventEnvelope envelope() {
    return new DomainEventEnvelope(
        "event-id",
        PaymentConfirmedEvent.EVENT_NAME,
        Instant.now(),
        PaymentConfirmedEvent.TOPIC,
        PAYLOAD,
        null);
  }

  private PaymentConfirmedEvent event() {
    return new PaymentConfirmedEvent(1L, BOOKING_ID, SEAT_ID, 4L, 50000L, PAID_AT);
  }

  /** Inbox가 최초 수신으로 판정해 비즈니스 콜백을 실행하고 true를 반환하도록 스텁한다. */
  private void givenInboxProcessesFirst() {
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.BOOKING),
                any(DomainEventEnvelope.class),
                any(Runnable.class)))
        .willAnswer(
            invocation -> {
              invocation.getArgument(2, Runnable.class).run();
              return true;
            });
  }

  /**
   * seat-service 가 "그 좌석이 이 예매의 것이 아님"(SEAT_409_003)으로 거부한 응답을 만든다.
   *
   * <p>본문이 실려 있어야 보상 경로로 갈린다 — 리스너는 상태 코드가 아니라 code 로 판정한다(#489).
   */
  private HttpClientErrorException seatNotOwnedConflict() {
    return conflictWithBody(
        "{\"is_success\":false,\"code\":\"" + ErrorStatus.SEAT_CONFIRM_NOT_OWNED.getCode() + "\"}");
  }

  private HttpClientErrorException conflictWithBody(String body) {
    return HttpClientErrorException.create(
        HttpStatus.CONFLICT,
        "Conflict",
        HttpHeaders.EMPTY,
        body.getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("최초 수신이면 예매를 확정하고 좌석 SOLD를 확정한 뒤 오프셋을 커밋한다")
  void handlePaymentConfirmed() {
    // given
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    given(bookingGetBookingNumberUseCase.execute(BOOKING_ID)).willReturn(BOOKING_NUMBER);

    // when
    listener.handlePaymentConfirmed(envelope, acknowledgment);

    // then
    verify(bookingConfirmUseCase).execute(BOOKING_ID, PAID_AT, SEAT_ID);
    verify(seatRestClient).confirmSold(BOOKING_NUMBER, SEAT_ID);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("이미 처리된 이벤트(inbox 중복)면 확정은 스킵하되, bookingNumber를 재조회해 좌석 SOLD는 재시도한다(회귀 방지)")
  void handlePaymentConfirmedRetriesSoldEvenWhenConfirmSkipped() {
    // given: Inbox가 이미 처리됨으로 판정 → 확정 콜백 미실행, false 반환.
    // 그럼에도 SOLD 재시도가 유실되지 않아야 한다(일시 SOLD 실패로 재소비된 상황).
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event());
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.BOOKING),
                any(DomainEventEnvelope.class),
                any(Runnable.class)))
        .willReturn(false);
    given(bookingGetBookingNumberUseCase.execute(BOOKING_ID)).willReturn(BOOKING_NUMBER);

    // when
    listener.handlePaymentConfirmed(envelope, acknowledgment);

    // then: 확정은 실행되지 않았지만 bookingNumber 재조회로 SOLD가 다시 호출된다
    verify(bookingConfirmUseCase, never()).execute(anyLong(), any(), anyLong());
    verify(bookingGetBookingNumberUseCase).execute(BOOKING_ID);
    verify(seatRestClient).confirmSold(BOOKING_NUMBER, SEAT_ID);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("seatId 검증 실패 등으로 확정이 예외를 던지면 SOLD 조회·호출 없이 오프셋만 커밋한다")
  void handlePaymentConfirmedSkipsSoldWhenConfirmFails() {
    // given: 확정 콜백이 BusinessException을 던져 runIfFirst 밖으로 전파
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new BusinessException(ErrorStatus.BOOKING_SEAT_MISMATCH))
        .given(bookingConfirmUseCase)
        .execute(BOOKING_ID, PAID_AT, SEAT_ID);

    // when & then: 예외를 리스너 밖으로 전파하지 않는다(파티션 블로킹 방지)
    assertThatCode(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .doesNotThrowAnyException();

    // then: 확정 실패 시 bookingNumber 조회·SOLD 확정은 호출되지 않고, 오프셋은 커밋된다
    verify(bookingGetBookingNumberUseCase, never()).execute(anyLong());
    verify(seatRestClient, never()).confirmSold(any(), anyLong());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("좌석 SOLD 확정이 409로 실패하면 CRITICAL 로그 후 보상 신호를 발행하고 오프셋을 커밋한다")
  void handlePaymentConfirmedPublishesSignalWhenSoldReturns409() {
    // given: 409는 이제 "좌석이 이 예매의 것이 아님"만 뜻한다(#489). 정상 중복은 seat가 200으로 답한다.
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    given(bookingGetBookingNumberUseCase.execute(BOOKING_ID)).willReturn(BOOKING_NUMBER);
    willThrow(seatNotOwnedConflict()).given(seatRestClient).confirmSold(BOOKING_NUMBER, SEAT_ID);

    // when & then: 4xx는 결정적이라 삼키고 커밋하되, 보상이 가능하도록 신호를 남긴다
    assertThatCode(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .doesNotThrowAnyException();

    // then
    verify(seatRestClient).confirmSold(BOOKING_NUMBER, SEAT_ID);
    verify(bookingPublishSeatConfirmFailedUseCase).execute(BOOKING_ID);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("구버전 seat의 정상 중복 409(SEAT_409_001)에는 보상 신호를 발행하지 않는다")
  void handlePaymentConfirmedIgnoresLegacyDuplicateConflict() {
    // given: #489 이전 seat-service 는 정상 중복(이미 같은 예매로 SOLD)에도 SEAT_409_001 을 준다.
    // 배포 순서가 뒤바뀌거나 seat 만 롤백되면 이 응답이 도착하는데, 그때 보상 신호를 내보내면
    // 멀쩡한 결제가 환불된다. 상태 코드가 아니라 code 로 갈라야 하는 이유다.
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    given(bookingGetBookingNumberUseCase.execute(BOOKING_ID)).willReturn(BOOKING_NUMBER);
    willThrow(
            conflictWithBody(
                "{\"is_success\":false,\"code\":\""
                    + ErrorStatus.SEAT_NOT_AVAILABLE.getCode()
                    + "\"}"))
        .given(seatRestClient)
        .confirmSold(BOOKING_NUMBER, SEAT_ID);

    // when & then
    assertThatCode(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .doesNotThrowAnyException();

    verify(bookingPublishSeatConfirmFailedUseCase, never()).execute(anyLong());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("응답 본문이 없는 409에는 보상 신호를 발행하지 않는다(fail-closed)")
  void handlePaymentConfirmedIgnoresConflictWithoutBody() {
    // given: 낙관적 락 충돌(COMMON_409)이나 프록시가 본문을 지운 경우. 근거가 불확실하면 보상하지 않는다 —
    // 잘못된 환불보다 놓친 알림이 낫다.
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    given(bookingGetBookingNumberUseCase.execute(BOOKING_ID)).willReturn(BOOKING_NUMBER);
    willThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", null, null, null))
        .given(seatRestClient)
        .confirmSold(BOOKING_NUMBER, SEAT_ID);

    // when & then
    assertThatCode(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .doesNotThrowAnyException();

    verify(bookingPublishSeatConfirmFailedUseCase, never()).execute(anyLong());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("좌석 SOLD 확정이 409가 아닌 4xx(예: 404)면 보상 신호를 발행하지 않고 오프셋을 커밋한다")
  void handlePaymentConfirmedAcksWhenSoldReturnsNon409_4xx() {
    // given: 404 등은 설정/요청 오류 의심 구간이라 CRITICAL로 남기되, 좌석 소유 문제가 아니므로 보상 대상이 아니다
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    given(bookingGetBookingNumberUseCase.execute(BOOKING_ID)).willReturn(BOOKING_NUMBER);
    willThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null))
        .given(seatRestClient)
        .confirmSold(BOOKING_NUMBER, SEAT_ID);

    // when & then
    assertThatCode(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .doesNotThrowAnyException();

    verify(bookingPublishSeatConfirmFailedUseCase, never()).execute(anyLong());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("409 후 보상 신호 발행이 일시(인프라) 오류로 실패하면 예외를 전파하고 오프셋을 커밋하지 않는다")
  void handlePaymentConfirmedRethrowsWhenSignalPublishFailsTransiently() {
    // given: 신호가 유실되면 보상 경로가 통째로 사라지므로, 재시도→DLT로 보존해야 한다
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    given(bookingGetBookingNumberUseCase.execute(BOOKING_ID)).willReturn(BOOKING_NUMBER);
    willThrow(seatNotOwnedConflict()).given(seatRestClient).confirmSold(BOOKING_NUMBER, SEAT_ID);
    willThrow(new DataIntegrityViolationException("db down"))
        .given(bookingPublishSeatConfirmFailedUseCase)
        .execute(BOOKING_ID);

    // when & then
    assertThatThrownBy(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .isInstanceOf(DataIntegrityViolationException.class);

    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("409 후 보상 신호 발행이 예매 없음(영구 실패)으로 끝나면 CRITICAL 로그 후 오프셋을 커밋한다")
  void handlePaymentConfirmedAcksWhenSignalPublishFailsPermanently() {
    // given: 재시도해도 없는 예매는 생기지 않으므로 재시도 파이프라인에 태우지 않는다
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    given(bookingGetBookingNumberUseCase.execute(BOOKING_ID)).willReturn(BOOKING_NUMBER);
    willThrow(seatNotOwnedConflict()).given(seatRestClient).confirmSold(BOOKING_NUMBER, SEAT_ID);
    willThrow(new BusinessException(ErrorStatus.BOOKING_NOT_FOUND))
        .given(bookingPublishSeatConfirmFailedUseCase)
        .execute(BOOKING_ID);

    // when & then
    assertThatCode(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("좌석 SOLD 확정이 5xx(일시 인프라 오류)로 실패하면 예외를 전파하고 오프셋을 커밋하지 않는다(재시도→DLT 위임)")
  void handlePaymentConfirmedRethrowsWhenSoldReturns5xx() {
    // given: 5xx는 일시 오류라 재시도하면 성공할 수 있다
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    given(bookingGetBookingNumberUseCase.execute(BOOKING_ID)).willReturn(BOOKING_NUMBER);
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
  @DisplayName("확정이 일시적(인프라) 예외를 던지면 예외를 전파하고 오프셋을 커밋하지 않는다")
  void handlePaymentConfirmedRethrowsTransientConfirmFailure() {
    // given: 일반 RuntimeException은 일시 실패로 분류된다
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new RuntimeException("DB 일시 장애"))
        .given(bookingConfirmUseCase)
        .execute(BOOKING_ID, PAID_AT, SEAT_ID);

    // when & then
    assertThatThrownBy(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .isInstanceOf(RuntimeException.class);

    // then: SOLD는 호출되지 않고 오프셋도 커밋되지 않는다
    verify(seatRestClient, never()).confirmSold(any(), anyLong());
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("동시 중복 수신으로 inbox unique가 경합해도(DIVE) bookingNumber 재조회 후 SOLD를 멱등 재시도하고 커밋한다")
  void handlePaymentConfirmedRetriesSoldOnInboxRace() {
    // given: 경합에서 진 처리(DIVE). 확정은 승자가 담당하나, SOLD 유실을 막기 위해 패자도 SOLD를 멱등 재시도해야 한다.
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class)).willReturn(event());
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.BOOKING),
                any(DomainEventEnvelope.class),
                any(Runnable.class)))
        .willThrow(
            new DuplicateEventException(
                KafkaConsumerGroup.BOOKING,
                "event-id",
                new DataIntegrityViolationException("duplicate")));
    given(bookingGetBookingNumberUseCase.execute(BOOKING_ID)).willReturn(BOOKING_NUMBER);

    // when
    listener.handlePaymentConfirmed(envelope, acknowledgment);

    // then: 확정은 재수행하지 않되(경합 패자), bookingNumber 재조회 후 SOLD는 멱등하게 재시도하고 커밋한다
    verify(bookingConfirmUseCase, never()).execute(anyLong(), any(), anyLong());
    verify(bookingGetBookingNumberUseCase).execute(BOOKING_ID);
    verify(seatRestClient).confirmSold(BOOKING_NUMBER, SEAT_ID);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("이벤트 역직렬화에 실패해도 예외를 전파하지 않고 오프셋을 커밋한다")
  void handlePaymentConfirmedSwallowsDeserializationException() {
    // given: 실제 JsonConverter가 변환 실패 시 던지는 예외 타입으로 스텁한다
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, PaymentConfirmedEvent.class))
        .willThrow(new DeserializationException(new RuntimeException("broken payload")));

    // when & then: 예외를 리스너 밖으로 전파하지 않는다
    assertThatCode(() -> listener.handlePaymentConfirmed(envelope, acknowledgment))
        .doesNotThrowAnyException();

    // then: 확정·SOLD는 호출되지 않고, 오프셋은 커밋된다
    verify(bookingConfirmUseCase, never()).execute(anyLong(), any(), anyLong());
    verify(seatRestClient, never()).confirmSold(any(), anyLong());
    verify(acknowledgment).acknowledge();
  }
}
