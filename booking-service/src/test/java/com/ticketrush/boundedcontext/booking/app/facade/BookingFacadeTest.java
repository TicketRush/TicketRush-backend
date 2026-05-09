package com.ticketrush.boundedcontext.booking.app.facade;

import static com.ticketrush.global.status.ErrorStatus.SEAT_ALREADY_LOCKED;
import static com.ticketrush.global.status.ErrorStatus.USER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.booking.app.dto.request.BookingCreateRequest;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCreateUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingIssueNumberUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateReferencesUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateSeatAvailableUseCase;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingFacadeTest {

  @InjectMocks private BookingFacade bookingFacade;

  @Mock private BookingIssueNumberUseCase bookingIssueNumberUseCase;
  @Mock private BookingCreateUseCase bookingCreateUseCase;
  @Mock private BookingValidateReferencesUseCase bookingValidateReferencesUseCase;
  @Mock private BookingValidateSeatAvailableUseCase bookingValidateSeatAvailableUseCase;

  @Test
  @DisplayName("성공: 참조 검증 후 예약번호를 발급하고 예매를 생성한다")
  void createBooking_success() {
    // given
    Long userId = 1L;
    Long performanceId = 2L;
    Long seatId = 3L;
    String bookingNumber = "BOOK-1234";
    Booking booking =
        Booking.builder()
            .userId(userId)
            .performanceId(performanceId)
            .seatId(seatId)
            .bookingNumber(bookingNumber)
            .build();

    given(bookingIssueNumberUseCase.execute()).willReturn(bookingNumber);
    given(
            bookingCreateUseCase.execute(
                new BookingCreateRequest(userId, performanceId, seatId, bookingNumber)))
        .willReturn(booking);

    // when
    Booking result = bookingFacade.createBooking(userId, performanceId, seatId);

    // then
    assertThat(result).isSameAs(booking);
    verify(bookingValidateReferencesUseCase).execute(userId, performanceId, seatId);
    verify(bookingValidateSeatAvailableUseCase).execute(seatId, performanceId);
  }

  @Test
  @DisplayName("실패: 참조 검증에 실패하면 예약번호 발급과 예매 생성을 하지 않는다")
  void createBooking_fail_when_reference_validation_fails() {
    // given
    Long userId = 1L;
    Long performanceId = 2L;
    Long seatId = 3L;

    doThrow(new BusinessException(USER_NOT_FOUND))
        .when(bookingValidateReferencesUseCase)
        .execute(userId, performanceId, seatId);

    // when & then
    assertThatThrownBy(() -> bookingFacade.createBooking(userId, performanceId, seatId))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(USER_NOT_FOUND);

    verifyNoInteractions(bookingValidateSeatAvailableUseCase);
    verifyNoInteractions(bookingIssueNumberUseCase, bookingCreateUseCase);
  }

  @Test
  @DisplayName("실패: 좌석 HOLD 검증에 실패하면 예약번호 발급과 예매 생성을 하지 않는다")
  void createBooking_fail_when_seat_is_held() {
    // given
    Long userId = 1L;
    Long performanceId = 2L;
    Long seatId = 3L;

    doThrow(new BusinessException(SEAT_ALREADY_LOCKED))
        .when(bookingValidateSeatAvailableUseCase)
        .execute(seatId, performanceId);

    // when & then
    assertThatThrownBy(() -> bookingFacade.createBooking(userId, performanceId, seatId))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(SEAT_ALREADY_LOCKED);

    verify(bookingValidateReferencesUseCase).execute(userId, performanceId, seatId);
    verifyNoInteractions(bookingIssueNumberUseCase, bookingCreateUseCase);
  }
}
