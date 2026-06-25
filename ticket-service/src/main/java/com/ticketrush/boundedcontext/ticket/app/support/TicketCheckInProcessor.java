package com.ticketrush.boundedcontext.ticket.app.support;

import com.ticketrush.boundedcontext.ticket.app.dto.response.EntryCheckInResponse;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 입장권을 UNUSED -> USED로 1회만 전이시키는 조건부 UPDATE를 짧은 트랜잭션 경계에서 수행한다.
 *
 * <p>외부 동기 호출(검증)은 호출 측이 트랜잭션 밖에서 끝낸 뒤 ticketId만 넘기므로, 이 트랜잭션은 DB 작업만 담아 커넥션 점유를 최소화한다(REST 왕복이
 * 트랜잭션 경계 안에 들어오지 않게 한다). 동시 스캔 시 조건부 UPDATE의 영향행수로 승패를 가린다(1행이면 성공, 0행이면 경쟁 패자).
 */
@Component
@RequiredArgsConstructor
public class TicketCheckInProcessor {

  private final TicketRepository ticketRepository;

  @Transactional
  public EntryCheckInResponse markUsed(Long ticketId) {
    LocalDateTime usedAt = LocalDateTime.now();
    int updatedCount =
        ticketRepository.markUsedById(ticketId, usedAt, TicketStatus.USED, TicketStatus.UNUSED);

    if (updatedCount == 1) {
      return EntryCheckInResponse.of(ticketId, usedAt);
    }

    // 0행: UNUSED가 아니어서 전이되지 않음. 사전 검증을 통과했어도 동시 스캔 경쟁에서 졌거나 그 사이 상태가 바뀐 경우다.
    Ticket current =
        ticketRepository
            .findById(ticketId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.TICKET_NOT_FOUND));
    if (current.getTicketStatus() == TicketStatus.USED) {
      throw new BusinessException(ErrorStatus.TICKET_ALREADY_USED);
    }
    throw new BusinessException(ErrorStatus.TICKET_NOT_USABLE);
  }
}
