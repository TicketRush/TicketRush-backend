package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.app.dto.request.BookingCreateRequest;
import com.ticketrush.boundedcontext.booking.app.mapper.BookingMapper;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.shared.booking.event.BookingCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class BookingCreateUseCase {

  private final BookingRepository bookingRepository;
  private final BookingMapper bookingMapper;

  // 비즈니스 트랜잭션 내부에서 직접 발행해 상태 변경과 이벤트 기록의 원자성을 보장한다.
  private final EventPublisher eventPublisher;

  public Booking execute(BookingCreateRequest request) {
    Booking booking = bookingMapper.toEntity(request);
    Booking savedBooking = bookingRepository.save(booking);

    BookingCreatedEvent event =
        new BookingCreatedEvent(
            savedBooking.getId(),
            request.bookingNumber(),
            request.seatId(),
            request.performanceId(),
            request.userId());

    eventPublisher.publish(event);

    return savedBooking;
  }
}
