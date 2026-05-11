package com.ticketrush.boundedcontext.booking.app.usecase;

import static com.ticketrush.global.status.ErrorStatus.PERFORMANCE_NOT_FOUND;
import static com.ticketrush.global.status.ErrorStatus.SEAT_NOT_FOUND;
import static com.ticketrush.global.status.ErrorStatus.USER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.ticketrush.boundedcontext.booking.out.repository.BookingReferenceReader;
import com.ticketrush.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingValidateReferencesUseCaseTest {

  @InjectMocks private BookingValidateReferencesUseCase bookingValidateReferencesUseCase;

  @Mock private BookingReferenceReader bookingReferenceReader;

  @Test
  @DisplayName("성공: 사용자, 공연, 좌석이 모두 존재하면 검증을 통과한다")
  void execute_success() {
    // given
    Long userId = 1L;
    Long performanceId = 2L;
    Long seatId = 3L;

    given(bookingReferenceReader.existsUserById(userId)).willReturn(true);
    given(bookingReferenceReader.existsPerformanceById(performanceId)).willReturn(true);
    given(bookingReferenceReader.existsSeatByIdAndPerformanceId(seatId, performanceId))
        .willReturn(true);

    // when
    bookingValidateReferencesUseCase.execute(userId, performanceId, seatId);

    // then
    verify(bookingReferenceReader).existsUserById(userId);
    verify(bookingReferenceReader).existsPerformanceById(performanceId);
    verify(bookingReferenceReader).existsSeatByIdAndPerformanceId(seatId, performanceId);
  }

  @Test
  @DisplayName("실패: 사용자가 존재하지 않으면 USER_NOT_FOUND 예외가 발생한다")
  void execute_fail_when_user_not_found() {
    // given
    Long userId = 1L;
    Long performanceId = 2L;
    Long seatId = 3L;

    given(bookingReferenceReader.existsUserById(userId)).willReturn(false);

    // when & then
    assertThatThrownBy(
            () -> bookingValidateReferencesUseCase.execute(userId, performanceId, seatId))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(USER_NOT_FOUND);

    verify(bookingReferenceReader).existsUserById(userId);
    verifyNoMoreInteractions(bookingReferenceReader);
  }

  @Test
  @DisplayName("실패: 공연이 존재하지 않으면 PERFORMANCE_NOT_FOUND 예외가 발생한다")
  void execute_fail_when_performance_not_found() {
    // given
    Long userId = 1L;
    Long performanceId = 2L;
    Long seatId = 3L;

    given(bookingReferenceReader.existsUserById(userId)).willReturn(true);
    given(bookingReferenceReader.existsPerformanceById(performanceId)).willReturn(false);

    // when & then
    assertThatThrownBy(
            () -> bookingValidateReferencesUseCase.execute(userId, performanceId, seatId))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(PERFORMANCE_NOT_FOUND);

    verify(bookingReferenceReader).existsUserById(userId);
    verify(bookingReferenceReader).existsPerformanceById(performanceId);
    verifyNoMoreInteractions(bookingReferenceReader);
  }

  @Test
  @DisplayName("실패: 공연에 해당 좌석이 존재하지 않으면 SEAT_NOT_FOUND 예외가 발생한다")
  void execute_fail_when_seat_not_found() {
    // given
    Long userId = 1L;
    Long performanceId = 2L;
    Long seatId = 3L;

    given(bookingReferenceReader.existsUserById(userId)).willReturn(true);
    given(bookingReferenceReader.existsPerformanceById(performanceId)).willReturn(true);
    given(bookingReferenceReader.existsSeatByIdAndPerformanceId(seatId, performanceId))
        .willReturn(false);

    // when & then
    assertThatThrownBy(
            () -> bookingValidateReferencesUseCase.execute(userId, performanceId, seatId))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(SEAT_NOT_FOUND);

    verify(bookingReferenceReader).existsUserById(userId);
    verify(bookingReferenceReader).existsPerformanceById(performanceId);
    verify(bookingReferenceReader).existsSeatByIdAndPerformanceId(seatId, performanceId);
  }
}
