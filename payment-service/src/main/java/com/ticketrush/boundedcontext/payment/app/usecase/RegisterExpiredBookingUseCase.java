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
 * existsByBookingId 검사로 대부분의 중복 수신(재전송)을 예외 없이 빠르게 거른다.
 * 멱등성의 최종 보장은 bookingId unique 제약이며, 경합으로 검사를 빠져나간 동시 수신은
 * 제약 위반(DataIntegrityViolationException)으로 막고 리스너가 이를 중복(정상)으로 간주해 ack 한다.
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
