package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.app.dto.request.BookingCreateRequest;
import com.ticketrush.boundedcontext.booking.app.mapper.BookingMapper;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.shared.booking.event.BookingCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher; // 변경된 부분
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class BookingCreateUseCase {

  private final BookingRepository bookingRepository;
  private final BookingMapper bookingMapper;

  // 비동기적으로 이벤트를 발행하기 위해 기존 EventPublisher 대신 Spring 기본 퍼블리셔 사용
  private final ApplicationEventPublisher applicationEventPublisher;

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

    applicationEventPublisher.publishEvent(event);

    return savedBooking;
  }
}
