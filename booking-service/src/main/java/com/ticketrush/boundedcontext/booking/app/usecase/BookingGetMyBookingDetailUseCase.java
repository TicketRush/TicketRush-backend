package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 본인 예매 단건 조회 (#560). DB 조회만 담당한다 — 타 도메인 보강은 트랜잭션 밖(Facade)에서 한다.
 *
 * <p>조회 자체를 소유자 조건으로 걸어({@code findByBookingNumberAndUserId}) 타인 예매와 미존재를 같은 404로 수렴시킨다 — 응답 코드로 타인
 * 예매의 존재 여부가 새지 않게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookingGetMyBookingDetailUseCase {

  private final BookingRepository bookingRepository;

  public Booking execute(Long userId, String bookingNumber) {
    return bookingRepository
        .findByBookingNumberAndUserId(bookingNumber, userId)
        .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));
  }
}
