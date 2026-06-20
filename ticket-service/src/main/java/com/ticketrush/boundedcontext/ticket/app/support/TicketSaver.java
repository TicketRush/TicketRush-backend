package com.ticketrush.boundedcontext.ticket.app.support;

import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 티켓 저장을 독립된 트랜잭션 경계(REQUIRES_NEW)에서 수행한다.
 *
 * <p>unique 제약 충돌로 INSERT가 실패하면 해당 트랜잭션만 rollback-only로 마킹되고, 발급 유스케이스의 바깥 트랜잭션은 오염되지 않는다. 덕분에
 * 유스케이스가 충돌을 catch한 뒤에도 커밋 시점에 {@code UnexpectedRollbackException}이 발생하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TicketSaver {

  private final TicketRepository ticketRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void saveInNewTransaction(Ticket ticket) {
    ticketRepository.saveAndFlush(ticket);
  }
}
