package com.ticketrush.boundedcontext.ticket.app.usecase;

import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketTokenGenerator;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketTokenHasher;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class TicketIssueUseCase {

  private final TicketRepository ticketRepository;
  private final TicketTokenGenerator ticketTokenGenerator;
  private final TicketTokenHasher ticketTokenHasher;

  public void execute(Long bookingId) {
    if (ticketRepository.existsByBookingId(bookingId)) {
      return;
    }

    String token = ticketTokenGenerator.generate();
    Ticket ticket =
        Ticket.builder()
            .bookingId(bookingId)
            .ticketTokenHash(ticketTokenHasher.hash(token))
            .ticketStatus(TicketStatus.UNUSED)
            .build();

    ticketRepository.save(ticket);
  }
}
