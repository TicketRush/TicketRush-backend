package com.ticketrush.boundedcontext.ticket.app.usecase;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketIssueResponse;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketTokenGenerator;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketTokenHasher;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 티켓 발급 유스케이스.
 *
 * <p>중복 방지는 상위 리스너의 Inbox(#110)가 담당한다. 이 유스케이스는 {@code InboxService.runIfFirst}의 트랜잭션(REQUIRED)에
 * 조인해, 티켓 저장과 Inbox 기록이 하나의 원자 단위로 커밋되도록 한다(과거 {@code TicketSaver}의 {@code REQUIRES_NEW}는 이 원자성을
 * 깨뜨려 제거함).
 *
 * <p>{@code existsByBookingId} 선체크는 방어 심층화이며, unique 충돌은 그대로 전파해 트랜잭션을 롤백시킨다(Inbox 미기록). 롤백된 이벤트는
 * {@code DataIntegrityViolationException}이 {@code BusinessException}이 아니라 #269상 일시(transient) 실패로
 * 분류되어 재소비되고, 재소비 시 self-heal 된다: booking_id 충돌은 재조회로 {@code alreadyIssued} 흡수, ticket_token_hash
 * 충돌은 토큰 재생성으로 해소된다.
 */
@Service
@Transactional
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

    // Inbox 트랜잭션에 조인된 상태로 저장한다. unique(booking_id/ticket_token_hash) 충돌 시 예외를 그대로 전파해
    // 트랜잭션을 롤백시키고(Inbox 미기록) 재소비로 처리한다.
    ticketRepository.saveAndFlush(ticket);
    return TicketIssueResponse.issued(token);
  }
}
