package com.ticketrush.boundedcontext.booking.out.repository;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {

  List<Booking> findByUserIdAndBookingStatusOrderByConfirmedAtDescCreatedAtDesc(
      Long userId, BookingStatus bookingStatus);

  long countByUserIdAndBookingStatus(Long userId, BookingStatus bookingStatus);
}
