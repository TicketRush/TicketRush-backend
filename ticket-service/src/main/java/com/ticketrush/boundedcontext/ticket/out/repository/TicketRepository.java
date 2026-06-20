package com.ticketrush.boundedcontext.ticket.out.repository;

import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

  boolean existsByBookingId(Long bookingId);
}
