package com.ticketrush.boundedcontext.booking.out.repository;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

  Page<Booking> findByUserIdAndBookingStatus(
      Long userId, BookingStatus bookingStatus, Pageable pageable);

  long countByUserIdAndBookingStatus(Long userId, BookingStatus bookingStatus);

  @Query(
      "SELECT b.id FROM Booking b "
          + "WHERE b.bookingStatus = :bookingStatus AND b.createdAt <= :cutoff "
          + "ORDER BY b.id ASC")
  List<Long> findExpiredPendingBookingIds(
      @Param("bookingStatus") BookingStatus bookingStatus,
      @Param("cutoff") LocalDateTime cutoff,
      Pageable pageable);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE Booking b SET b.bookingStatus = :expiredStatus "
          + "WHERE b.id = :bookingId AND b.bookingStatus = :pendingStatus")
  int expirePendingBookingById(
      @Param("bookingId") Long bookingId,
      @Param("pendingStatus") BookingStatus pendingStatus,
      @Param("expiredStatus") BookingStatus expiredStatus);
}
