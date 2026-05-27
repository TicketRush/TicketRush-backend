package com.ticketrush.boundedcontext.ticket.app.usecase;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketIssueResponse;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketTokenGenerator;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketTokenHasher;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketIssueUseCase {

  private final TicketRepository ticketRepository;
  private final TicketTokenGenerator ticketTokenGenerator;
  private final TicketTokenHasher ticketTokenHasher;

  public TicketIssueResponse execute(Long bookingId) {
    if (ticketRepository.existsByBookingId(bookingId)) {
      return TicketIssueResponse.alreadyIssued();
    }

    String token = ticketTokenGenerator.generate();
    Ticket ticket =
        Ticket.builder()
            .bookingId(bookingId)
            .ticketTokenHash(ticketTokenHasher.hash(token))
            .ticketStatus(TicketStatus.UNUSED)
            .build();

    try {
      ticketRepository.saveAndFlush(ticket);
      return TicketIssueResponse.issued(token);
    } catch (DataIntegrityViolationException e) {
      return TicketIssueResponse.alreadyIssued();
    }
  }
}
