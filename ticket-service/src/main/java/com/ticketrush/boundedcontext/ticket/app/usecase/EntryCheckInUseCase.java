package com.ticketrush.boundedcontext.ticket.app.usecase;

import com.ticketrush.boundedcontext.ticket.app.dto.response.EntryCheckInResponse;
import com.ticketrush.boundedcontext.ticket.app.support.TicketCheckInProcessor;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EntryCheckInUseCase {

  private final EntryVerifyUseCase entryVerifyUseCase;
  private final TicketCheckInProcessor ticketCheckInProcessor;

  /**
   * 검증을 통과한 입장권을 입장 처리(UNUSED -> USED)한다.
   *
   * <p>외부 동기 호출(booking)을 포함한 사전 검증({@link EntryVerifyUseCase#verifyAndLoad})은 트랜잭션 밖에서 수행하고, 상태
   * 전이(조건부 UPDATE)만 짧은 트랜잭션({@link TicketCheckInProcessor#markUsed})에 담아 DB 커넥션 점유를 최소화한다. 조건부
   * UPDATE 자체가 멱등·원자적이라, 검증과 전이를 한 트랜잭션으로 묶지 않아도 중복 입장은 막힌다.
   */
  public EntryCheckInResponse execute(String token) {
    Ticket ticket = entryVerifyUseCase.verifyAndLoad(token);
    return ticketCheckInProcessor.markUsed(ticket.getId());
  }
}
