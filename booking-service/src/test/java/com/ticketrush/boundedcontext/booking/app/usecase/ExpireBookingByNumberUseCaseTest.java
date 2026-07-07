package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.shared.booking.event.BookingExpiredEvent;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExpireBookingByNumberUseCaseTest {

  @Mock private BookingRepository bookingRepository;
  @Mock private EventPublisher eventPublisher;

  @InjectMocks private ExpireBookingByNumberUseCase expireBookingByNumberUseCase;

  private static final String BOOKING_NUMBER = "BK-1";
  private static final Long BOOKING_ID = 10L;
  private static final LocalDateTime EXPIRED_AT = LocalDateTime.now();

  @Test
  @DisplayName("PENDING 예매를 EXPIRED로 전이하면 BookingExpiredEvent를 발행한다")
  void execute_WhenTransitioned_PublishesBookingExpiredEvent() {
    // given
    given(bookingRepository.findIdByBookingNumber(BOOKING_NUMBER))
        .willReturn(Optional.of(BOOKING_ID));
    given(
            bookingRepository.expirePendingBookingById(
                BOOKING_ID, BookingStatus.PENDING, BookingStatus.EXPIRED))
        .willReturn(1);

    // when
    expireBookingByNumberUseCase.execute(BOOKING_NUMBER, EXPIRED_AT);

    // then
    verify(eventPublisher).publish(new BookingExpiredEvent(BOOKING_ID, EXPIRED_AT));
  }

  @Test
  @DisplayName("이미 EXPIRED/CONFIRMED 등이라 전이가 일어나지 않으면(updated==0) 이벤트를 발행하지 않는다(멱등)")
  void execute_WhenAlreadyTerminal_DoesNotPublish() {
    // given
    given(bookingRepository.findIdByBookingNumber(BOOKING_NUMBER))
        .willReturn(Optional.of(BOOKING_ID));
    given(
            bookingRepository.expirePendingBookingById(
                BOOKING_ID, BookingStatus.PENDING, BookingStatus.EXPIRED))
        .willReturn(0);

    // when
    expireBookingByNumberUseCase.execute(BOOKING_NUMBER, EXPIRED_AT);

    // then
    verify(eventPublisher, never()).publish(any());
  }

  @Test
  @DisplayName("대상 예매를 찾을 수 없으면 만료 처리를 스킵하고 이벤트를 발행하지 않는다")
  void execute_WhenBookingNotFound_Skips() {
    // given
    given(bookingRepository.findIdByBookingNumber(BOOKING_NUMBER)).willReturn(Optional.empty());

    // when
    expireBookingByNumberUseCase.execute(BOOKING_NUMBER, EXPIRED_AT);

    // then
    verify(bookingRepository, never())
        .expirePendingBookingById(any(), eq(BookingStatus.PENDING), eq(BookingStatus.EXPIRED));
    verifyNoInteractions(eventPublisher);
  }
}
