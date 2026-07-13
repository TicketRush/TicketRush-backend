package com.ticketrush.boundedcontext.ticket.app.usecase;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketInternalResponse;
import com.ticketrush.boundedcontext.ticket.app.mapper.TicketMapper;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예매의 입장권 상태를 다른 서비스에 동기 노출한다 (#399).
 *
 * <p>입장 완료(USED) 예매의 환불을 booking-service가 차단하려면 티켓 사용 여부를 알아야 하는데, ADR 3(데이터 캡슐화)에 따라 ticket 테이블을
 * 직접 읽을 수 없다.
 *
 * <p>계약은 여기까지다 — <b>발급된 입장권이 없으면 {@code TICKET_404_001}로 응답한다.</b> 그 404를 무엇으로 해석할지는 소비자의 몫이므로 여기에
 * 적지 않는다. 다만 소비자가 다른 404(경로 오설정 등)와 구분할 수 있어야 하므로, 이 코드로 응답한다는 사실 자체는 계약이다.
 */
@Service
@RequiredArgsConstructor
public class TicketGetInternalUseCase {

  private final TicketRepository ticketRepository;
  private final TicketMapper ticketMapper;

  @Transactional(readOnly = true)
  public TicketInternalResponse execute(Long bookingId) {
    Ticket ticket =
        ticketRepository
            .findByBookingId(bookingId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.TICKET_NOT_FOUND));
    return ticketMapper.toTicketInternalResponse(ticket);
  }
}
