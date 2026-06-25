package com.ticketrush.boundedcontext.ticket.app.usecase;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketQrResponse;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.policy.QrPayload;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketQrPayloadGenerator;
import com.ticketrush.boundedcontext.ticket.out.apiclient.BookingRestClient;
import com.ticketrush.boundedcontext.ticket.out.apiclient.dto.BookingInfoResponse;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketQrGetUseCase {

  /** 확정된(CONFIRMED) 예매만 QR payload 조회를 허용한다. booking-service의 BookingStatus enum 이름과 일치. */
  private static final String CONFIRMED_STATUS = "CONFIRMED";

  private final BookingRestClient bookingRestClient;
  private final TicketRepository ticketRepository;
  private final TicketQrPayloadGenerator ticketQrPayloadGenerator;

  /**
   * 외부 동기 호출(booking-service)을 트랜잭션 밖에서 먼저 수행해 DB 커넥션을 장시간 점유하지 않는다. 실제 DB 접근은 findByBookingId
   * 단건뿐이라 Spring Data의 기본 트랜잭션으로 충분하므로 메서드 레벨 트랜잭션을 두지 않는다.
   */
  public TicketQrResponse execute(Long userId, Long bookingId) {
    BookingInfoResponse booking = bookingRestClient.getBooking(bookingId);

    // 본인 예매가 아니면 미존재와 동일하게 404로 통일해 다른 사용자 예매의 존재 여부 노출을 막는다.
    if (!userId.equals(booking.userId())) {
      throw new BusinessException(ErrorStatus.TICKET_NOT_FOUND);
    }

    // 미확정/취소 예매는 입장권 조회가 불가능하다.
    if (!CONFIRMED_STATUS.equals(booking.bookingStatus())) {
      throw new BusinessException(ErrorStatus.TICKET_NOT_USABLE);
    }

    Ticket ticket =
        ticketRepository
            .findByBookingId(bookingId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.TICKET_NOT_FOUND));

    QrPayload qrPayload = ticketQrPayloadGenerator.generate(ticket);
    return TicketQrResponse.of(ticket, qrPayload.payload(), qrPayload.expiresAt());
  }
}
