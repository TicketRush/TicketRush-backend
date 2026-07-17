package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예매의 {@code bookingNumber}를 재조회한다.
 *
 * <p>결제 완료 처리에서 예매 확정은 Inbox({@code runIfFirst})로 감싸 중복 시 스킵되지만, 커밋 후 좌석 SOLD 확정 HTTP 호출은 Inbox 밖에서
 * 이뤄진다. SOLD가 일시 실패로 재소비될 때 확정이 스킵되더라도 SOLD 재호출에 필요한 {@code bookingNumber}를 확보할 수 있도록, 확정 결과 반환값에
 * 의존하지 않고 이 조회 경로로 다시 읽는다. {@code bookingNumber}는 예매 생성 시점에 부여되므로 확정 여부와 무관하게 존재한다.
 */
@Service
@RequiredArgsConstructor
public class BookingGetBookingNumberUseCase {

  private final BookingRepository bookingRepository;

  @Transactional(readOnly = true)
  public String execute(Long bookingId) {
    Booking booking =
        bookingRepository
            .findById(bookingId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));
    return booking.getBookingNumber();
  }
}
