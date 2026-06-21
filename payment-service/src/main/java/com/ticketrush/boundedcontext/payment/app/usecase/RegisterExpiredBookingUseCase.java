package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.domain.entity.ExpiredBooking;
import com.ticketrush.boundedcontext.payment.out.repository.ExpiredBookingRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * BookingExpiredEvent 수신 시 만료 bookingId를 영속화한다. (#224)
 * BookingExpiredEvent.key()가 bookingId라 동일 booking은 같은 파티션에서 순차 처리되므로,
 * existsByBookingId 검사만으로 중복 수신을 멱등 처리한다. unique 제약은 DB 레벨 backstop.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class RegisterExpiredBookingUseCase {

  private final ExpiredBookingRepository expiredBookingRepository;

  public void execute(Long bookingId, LocalDateTime expiredAt) {
    if (expiredBookingRepository.existsByBookingId(bookingId)) {
      return;
    }

    expiredBookingRepository.save(ExpiredBooking.of(bookingId, expiredAt));
  }
}
