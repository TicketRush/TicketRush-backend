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
 * 직접 읽을 수 없다. 티켓이 없으면 발급 전(또는 만료된 예매)이므로 입장했을 리 없다 — 404로 응답하고 호출 측이 "미입장"으로 해석한다.
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
