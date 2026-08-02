package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingGetMyBookingUseCaseTest {

  private static final Long USER_ID = 1L;
  private static final String BOOKING_NUMBER = "X7B29-KLPW1";

  @InjectMocks private BookingGetMyBookingUseCase useCase;

  @Mock private BookingRepository bookingRepository;

  @Test
  @DisplayName("성공: 본인 예매를 예매번호로 조회한다")
  void execute_returns_own_booking() {
    // given
    Booking booking =
        Booking.builder()
            .userId(USER_ID)
            .performanceId(2L)
            .seatId(3L)
            .bookingNumber(BOOKING_NUMBER)
            .bookingStatus(BookingStatus.CONFIRMED)
            .build();
    given(bookingRepository.findByBookingNumberAndUserId(BOOKING_NUMBER, USER_ID))
        .willReturn(Optional.of(booking));

    // when & then
    assertThat(useCase.execute(USER_ID, BOOKING_NUMBER)).isSameAs(booking);
  }

  @Test
  @DisplayName("실패: 타인 예매와 미존재 예매는 같은 경로(빈 조회)로 404를 던진다 — 존재 여부 비노출")
  void execute_throws_404_for_missing_or_others_booking() {
    // given
    given(bookingRepository.findByBookingNumberAndUserId(BOOKING_NUMBER, USER_ID))
        .willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> useCase.execute(USER_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.BOOKING_NOT_FOUND);
  }
}
