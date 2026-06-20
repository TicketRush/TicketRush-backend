package com.ticketrush.boundedcontext.ticket.app.usecase;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketIssueResponse;
import com.ticketrush.boundedcontext.ticket.app.support.TicketSaver;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketTokenGenerator;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketTokenHasher;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class TicketIssueUseCase {

  private final TicketRepository ticketRepository;
  private final TicketTokenGenerator ticketTokenGenerator;
  private final TicketTokenHasher ticketTokenHasher;
  private final TicketSaver ticketSaver;

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
      // 저장은 별도 트랜잭션(REQUIRES_NEW)에서 수행하므로, 충돌이 나도 이 유스케이스 트랜잭션은 정상 커밋된다.
      ticketSaver.saveInNewTransaction(ticket);
      return TicketIssueResponse.issued(token);
    } catch (DataIntegrityViolationException e) {
      // booking_id, ticket_token_hash 두 unique 제약 중 무엇이 깨졌는지 재조회로 구분한다.
      if (ticketRepository.existsByBookingId(bookingId)) {
        // booking_id 충돌 = 동시 발급 경쟁에서 진 것이므로 이미 발급된 것으로 간주한다.
        return TicketIssueResponse.alreadyIssued();
      }
      // ticket_token_hash 충돌 = 토큰 재생성 후 재시도하면 해소되므로, 삼키지 않고 던져 재시도를 유도한다.
      throw e;
    }
  }
}
