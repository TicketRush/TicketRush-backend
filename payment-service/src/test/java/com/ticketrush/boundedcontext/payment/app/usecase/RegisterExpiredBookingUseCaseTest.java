package com.ticketrush.boundedcontext.payment.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.domain.entity.ExpiredBooking;
import com.ticketrush.boundedcontext.payment.out.repository.ExpiredBookingRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegisterExpiredBookingUseCaseTest {

  @Mock private ExpiredBookingRepository expiredBookingRepository;

  @InjectMocks private RegisterExpiredBookingUseCase registerExpiredBookingUseCase;

  @Test
  @DisplayName("처음 수신한 만료 booking이면 영속화한다")
  void execute_saves_when_new() {
    // given
    Long bookingId = 100L;
    LocalDateTime expiredAt = LocalDateTime.of(2026, 6, 21, 10, 0);
    given(expiredBookingRepository.existsByBookingId(bookingId)).willReturn(false);

    // when
    registerExpiredBookingUseCase.execute(bookingId, expiredAt);

    // then
    ArgumentCaptor<ExpiredBooking> captor = ArgumentCaptor.forClass(ExpiredBooking.class);
    verify(expiredBookingRepository).save(captor.capture());
    assertThat(captor.getValue().getBookingId()).isEqualTo(bookingId);
    assertThat(captor.getValue().getExpiredAt()).isEqualTo(expiredAt);
  }

  @Test
  @DisplayName("이미 등록된 만료 booking이면 저장하지 않는다(멱등)")
  void execute_skips_when_already_registered() {
    // given
    Long bookingId = 100L;
    given(expiredBookingRepository.existsByBookingId(bookingId)).willReturn(true);

    // when
    registerExpiredBookingUseCase.execute(bookingId, LocalDateTime.of(2026, 6, 21, 10, 0));

    // then
    verify(expiredBookingRepository, never()).save(any());
  }
}
