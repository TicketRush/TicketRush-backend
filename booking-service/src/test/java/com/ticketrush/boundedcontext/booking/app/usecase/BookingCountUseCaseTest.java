package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingCountResponse;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingCountUseCaseTest {

  @InjectMocks private BookingCountUseCase bookingCountUseCase;

  @Mock private BookingRepository bookingRepository;

  @Test
  @DisplayName("성공: 회원 ID와 상태로 예매 수를 조회한다")
  void execute_success() {
    // given
    Long userId = 1L;
    given(bookingRepository.countByUserIdAndBookingStatus(userId, BookingStatus.CONFIRMED))
        .willReturn(3L);

    // when
    BookingCountResponse result = bookingCountUseCase.execute(userId, BookingStatus.CONFIRMED);

    // then
    assertThat(result.bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(result.count()).isEqualTo(3L);
    verify(bookingRepository).countByUserIdAndBookingStatus(userId, BookingStatus.CONFIRMED);
  }
}
