package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 예매 번호로 예매 1건을 조회한다 (#562).
 *
 * <p><b>왜 목록 API로 대신할 수 없는가.</b> {@code BookingGetAdminBookingsUseCase}는 검색·필터 없이 페이징만 제공하므로, 좌석
 * 관리자 화면이 좌석의 {@code bookingNumber} 하나로 예매자를 찾으려면 전체 페이지를 순차로 훑어야 한다 — 그 자체가 개인정보 전량 열람이다.
 *
 * <p>{@code BookingGetAdminBookingsUseCase}와 같은 규율로 엔티티를 반환한다. 타 도메인 보강(공연·좌석·예매자)은 트랜잭션 밖인 파사드가
 * 맡고, {@code Booking}에는 지연 로딩 연관이 없어 트랜잭션 밖 접근이 안전하다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingGetAdminBookingUseCase {

  private final BookingRepository bookingRepository;

  public Booking execute(String bookingNumber) {
    return bookingRepository
        .findByBookingNumber(bookingNumber)
        .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));
  }
}
