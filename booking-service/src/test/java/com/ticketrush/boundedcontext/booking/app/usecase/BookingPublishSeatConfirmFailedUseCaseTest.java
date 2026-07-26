package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.booking.event.SeatConfirmFailedEvent;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BookingPublishSeatConfirmFailedUseCaseTest {

  @InjectMocks
  private BookingPublishSeatConfirmFailedUseCase bookingPublishSeatConfirmFailedUseCase;

  @Mock private BookingRepository bookingRepository;
  @Mock private EventPublisher eventPublisher;

  private static final Long BOOKING_ID = 10L;
  private static final Long SEAT_ID = 3L;
  private static final Long USER_ID = 4L;
  private static final String BOOKING_NUMBER = "BOOK-1234";

  private Booking booking() {
    Booking booking =
        Booking.builder()
            .bookingNumber(BOOKING_NUMBER)
            .userId(USER_ID)
            .performanceId(1L)
            .seatId(SEAT_ID)
            .build();
    ReflectionTestUtils.setField(booking, "id", BOOKING_ID);
    return booking;
  }

  @Test
  @DisplayName("성공: 예매를 조회해 SeatConfirmFailedEvent를 발행한다")
  void execute_success() {
    // given
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking()));

    // when
    bookingPublishSeatConfirmFailedUseCase.execute(BOOKING_ID);

    // then — #492가 보상 환불을 걸 때 필요한 식별자가 전부 실려야 한다
    ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
    verify(eventPublisher).publish(captor.capture());

    assertThat(captor.getValue()).isInstanceOf(SeatConfirmFailedEvent.class);
    SeatConfirmFailedEvent event = (SeatConfirmFailedEvent) captor.getValue();
    assertThat(event.bookingId()).isEqualTo(BOOKING_ID);
    assertThat(event.bookingNumber()).isEqualTo(BOOKING_NUMBER);
    assertThat(event.seatId()).isEqualTo(SEAT_ID);
    assertThat(event.userId()).isEqualTo(USER_ID);
    assertThat(event.failedAt()).isNotNull();
  }

  @Test
  @DisplayName("실패: 예매가 없으면 BusinessException(BOOKING_NOT_FOUND)이 발생하고 발행하지 않는다")
  void execute_fail_booking_not_found() {
    // given
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> bookingPublishSeatConfirmFailedUseCase.execute(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.BOOKING_NOT_FOUND);

    verify(eventPublisher, never()).publish(any());
  }
}
