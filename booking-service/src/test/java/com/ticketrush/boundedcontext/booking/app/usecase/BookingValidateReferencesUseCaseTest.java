package com.ticketrush.boundedcontext.booking.app.usecase;

import static com.ticketrush.global.status.ErrorStatus.PERFORMANCE_NOT_FOUND;
import static com.ticketrush.global.status.ErrorStatus.PERFORMANCE_NOT_ON_SALE;
import static com.ticketrush.global.status.ErrorStatus.SEAT_NOT_FOUND;
import static com.ticketrush.global.status.ErrorStatus.USER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.ticketrush.boundedcontext.booking.out.repository.BookingReferenceReader;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.types.PerformanceStatus;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingValidateReferencesUseCaseTest {

  private static final Long USER_ID = 1L;
  private static final Long PERFORMANCE_ID = 2L;
  private static final Long SEAT_ID = 3L;

  @InjectMocks private BookingValidateReferencesUseCase bookingValidateReferencesUseCase;

  @Mock private BookingReferenceReader bookingReferenceReader;

  private void givenPerformanceStatus(PerformanceStatus status) {
    given(bookingReferenceReader.findPerformanceStatus(PERFORMANCE_ID))
        .willReturn(Optional.ofNullable(status));
  }

  private void assertRejectedWith(ErrorStatus expected) {
    assertThatThrownBy(
            () -> bookingValidateReferencesUseCase.execute(USER_ID, PERFORMANCE_ID, SEAT_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(expected);
  }

  @Test
  @DisplayName("성공: 사용자, ON_SALE 공연, 좌석이 모두 존재하면 검증을 통과한다 (#671)")
  void execute_success() {
    // given
    given(bookingReferenceReader.existsUserById(USER_ID)).willReturn(true);
    givenPerformanceStatus(PerformanceStatus.ON_SALE);
    given(bookingReferenceReader.existsSeatByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID))
        .willReturn(true);

    // when
    bookingValidateReferencesUseCase.execute(USER_ID, PERFORMANCE_ID, SEAT_ID);

    // then
    verify(bookingReferenceReader).existsUserById(USER_ID);
    verify(bookingReferenceReader).findPerformanceStatus(PERFORMANCE_ID);
    verify(bookingReferenceReader).existsSeatByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID);
  }

  @Test
  @DisplayName("실패: 사용자가 존재하지 않으면 USER_NOT_FOUND 예외가 발생한다")
  void execute_fail_when_user_not_found() {
    // given
    given(bookingReferenceReader.existsUserById(USER_ID)).willReturn(false);

    // when & then
    assertRejectedWith(USER_NOT_FOUND);

    verify(bookingReferenceReader).existsUserById(USER_ID);
    verifyNoMoreInteractions(bookingReferenceReader);
  }

  @Test
  @DisplayName("실패: 공연이 존재하지 않으면 PERFORMANCE_NOT_FOUND 예외가 발생한다")
  void execute_fail_when_performance_not_found() {
    // given — 상태 조회가 빈 Optional 이면 그 공연이 없다는 뜻이다.
    given(bookingReferenceReader.existsUserById(USER_ID)).willReturn(true);
    givenPerformanceStatus(null);

    // when & then
    assertRejectedWith(PERFORMANCE_NOT_FOUND);

    verify(bookingReferenceReader).existsUserById(USER_ID);
    verify(bookingReferenceReader).findPerformanceStatus(PERFORMANCE_ID);
    verifyNoMoreInteractions(bookingReferenceReader);
  }

  @ParameterizedTest
  @EnumSource(value = PerformanceStatus.class, names = "ON_SALE", mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("실패: ON_SALE 이 아닌 공연은 PERFORMANCE_400_005 로 차단한다 (#671)")
  void execute_fail_when_performance_not_on_sale(PerformanceStatus status) {
    // given — 오픈 전(UPCOMING) 직접 호출 차단이 이 이슈의 본체다. ON_SALE 만 제외한 EnumSource 로 돌려서,
    // 판매 상태가 새로 추가되면 "그것도 막힌다"가 자동으로 요구된다 — 통과는 ON_SALE 을 확인했을 때뿐이다.
    // (컬럼에 enum 에 없는 값이 있는 경우는 리더의 valueOf 가 던져 여기까지 오지 않는다.)
    given(bookingReferenceReader.existsUserById(USER_ID)).willReturn(true);
    givenPerformanceStatus(status);

    // when & then
    assertRejectedWith(PERFORMANCE_NOT_ON_SALE);

    // 좌석 조회까지 가지 않는다 — 오픈 전 공연의 좌석 존재 여부를 알려줄 이유가 없다.
    verify(bookingReferenceReader).existsUserById(USER_ID);
    verify(bookingReferenceReader).findPerformanceStatus(PERFORMANCE_ID);
    verifyNoMoreInteractions(bookingReferenceReader);
  }

  @Test
  @DisplayName("실패: 공연에 해당 좌석이 존재하지 않으면 SEAT_NOT_FOUND 예외가 발생한다")
  void execute_fail_when_seat_not_found() {
    // given
    given(bookingReferenceReader.existsUserById(USER_ID)).willReturn(true);
    givenPerformanceStatus(PerformanceStatus.ON_SALE);
    given(bookingReferenceReader.existsSeatByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID))
        .willReturn(false);

    // when & then
    assertRejectedWith(SEAT_NOT_FOUND);

    verify(bookingReferenceReader).existsUserById(USER_ID);
    verify(bookingReferenceReader).findPerformanceStatus(PERFORMANCE_ID);
    verify(bookingReferenceReader).existsSeatByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID);
  }
}
