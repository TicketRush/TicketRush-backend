package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingInternalResponse;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BookingGetInternalUseCaseTest {

  @InjectMocks private BookingGetInternalUseCase bookingGetInternalUseCase;

  @Mock private BookingRepository bookingRepository;

  @Test
  @DisplayName("성공: 예매 ID로 소유자·상태·예매번호를 반환한다")
  void execute_returns_owner_and_status() {
    // given
    Long bookingId = 100L;
    Booking booking =
        Booking.builder()
            .bookingNumber("BOOK-1")
            .userId(10L)
            .performanceId(2L)
            .seatId(3L)
            .bookingStatus(BookingStatus.CONFIRMED)
            .build();
    ReflectionTestUtils.setField(booking, "id", bookingId);
    given(bookingRepository.findById(bookingId)).willReturn(Optional.of(booking));

    // when
    BookingInternalResponse response = bookingGetInternalUseCase.execute(bookingId);

    // then
    assertThat(response.bookingId()).isEqualTo(bookingId);
    assertThat(response.userId()).isEqualTo(10L);
    assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    // payment 의 좌석 소유 교차검증이 이 값에 걸려 있다(#607). 매핑이 빠지면 null 로 나가고,
    // 그 null 은 호출자에서 "판정 불가"가 아니라 "검증 생략"으로 새기 쉬워 사고가 조용히 열린다.
    assertThat(response.bookingNumber()).isEqualTo("BOOK-1");
  }

  @Test
  @DisplayName("실패: 예매가 없으면 BOOKING_NOT_FOUND를 던진다")
  void execute_fails_when_not_found() {
    // given
    Long bookingId = 999L;
    given(bookingRepository.findById(bookingId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> bookingGetInternalUseCase.execute(bookingId))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.BOOKING_NOT_FOUND);
  }
}
