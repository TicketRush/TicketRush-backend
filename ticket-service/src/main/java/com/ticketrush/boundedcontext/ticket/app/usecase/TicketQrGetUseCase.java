package com.ticketrush.boundedcontext.ticket.app.usecase;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketQrResponse;
import com.ticketrush.boundedcontext.ticket.app.mapper.TicketMapper;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.policy.QrPayload;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketQrPayloadGenerator;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketQrGetUseCase {

  private final TicketRepository ticketRepository;
  private final TicketQrPayloadGenerator ticketQrPayloadGenerator;
  private final TicketMapper ticketMapper;

  /**
   * QR 조회는 booking-service 동기 호출 없이 로컬 데이터로만 처리한다(#364). 소유권은 발급 시 복제한 {@code ticket.userId}로, 유효성은
   * PaymentCanceledEvent가 투영한 로컬 {@code ticketStatus}로 판정한다.
   *
   * <p>REFUNDING/REFUND_FAILED 등 환불 진행 상태는 로컬에 반영되지 않아 이 경로에서 걸러지지 않는다. QR 조회는 되돌릴 수 없는 행위가 아니므로
   * 허용하고, 실제 입장 게이트({@code EntryVerifyUseCase})가 권위 있는 bookingStatus로 최종 차단한다.
   *
   * <p>DB 접근이 findByBookingId 단건뿐이라 Spring Data의 기본 트랜잭션으로 충분하므로 메서드 레벨 트랜잭션을 두지 않는다.
   */
  public TicketQrResponse execute(Long userId, Long bookingId) {
    Ticket ticket =
        ticketRepository
            .findByBookingId(bookingId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.TICKET_NOT_FOUND));

    // 본인 예매가 아니면 미존재와 동일하게 404로 통일해 다른 사용자 예매의 존재 여부 노출을 막는다.
    // backfill 되지 않은 과거 티켓(userId == null)도 여기서 404가 된다.
    if (!userId.equals(ticket.getUserId())) {
      throw new BusinessException(ErrorStatus.TICKET_NOT_FOUND);
    }

    // 취소된 예매는 입장권 조회가 불가능하다.
    if (ticket.getTicketStatus() == TicketStatus.CANCELED) {
      throw new BusinessException(ErrorStatus.TICKET_NOT_USABLE);
    }

    QrPayload qrPayload = ticketQrPayloadGenerator.generate(ticket);
    return ticketMapper.toTicketQrResponse(ticket, qrPayload.payload(), qrPayload.expiresAt());
  }
}
