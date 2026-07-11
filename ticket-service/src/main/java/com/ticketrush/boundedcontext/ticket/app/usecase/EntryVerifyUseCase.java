package com.ticketrush.boundedcontext.ticket.app.usecase;

import com.ticketrush.boundedcontext.ticket.app.dto.response.EntryVerifyResponse;
import com.ticketrush.boundedcontext.ticket.app.mapper.TicketMapper;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketQrPayloadVerifier;
import com.ticketrush.boundedcontext.ticket.domain.policy.VerifiedQrClaims;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.apiclient.BookingRestClient;
import com.ticketrush.boundedcontext.ticket.out.apiclient.dto.BookingInfoResponse;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EntryVerifyUseCase {

  /** 확정된(CONFIRMED) 예매만 입장 가능하다. booking-service의 BookingStatus enum 이름과 일치. */
  private static final String CONFIRMED_STATUS = "CONFIRMED";

  private final TicketQrPayloadVerifier ticketQrPayloadVerifier;
  private final TicketRepository ticketRepository;
  private final BookingRestClient bookingRestClient;
  private final TicketMapper ticketMapper;

  /** QR을 검증해 입장 가능 상태를 반환한다(상태 변경 없음). */
  public EntryVerifyResponse execute(String token) {
    return ticketMapper.toEntryVerifyResponse(verifyAndLoad(token));
  }

  /**
   * QR JWT 검증부터 입장 가능 입장권 로드까지의 공통 검증을 수행한다. verify와 check-in이 공유하며, 어느 한쪽도 검증을 건너뛰지 않도록 단일 진입점으로
   * 둔다. 두 경로 모두 트랜잭션 밖에서 호출된다(외부 동기 호출인 booking 조회를 트랜잭션 경계에 들이지 않기 위함). check-in의 상태 전이는 검증 이후 별도의
   * 짧은 트랜잭션({@code TicketCheckInProcessor#markUsed})에서 수행되며, 그 조건부 UPDATE가 멱등·원자적이라 검증과 전이를 한
   * 트랜잭션으로 묶지 않아도 중복 입장은 막힌다.
   *
   * <p>검증 순서: 서명/형식({@code TICKET_QR_INVALID}) → 만료({@code TICKET_QR_EXPIRED}) → 입장권 존재({@code
   * TICKET_NOT_FOUND}) → 예매 확정/취소({@code TICKET_NOT_USABLE}) → 이미 사용({@code TICKET_ALREADY_USED}).
   */
  public Ticket verifyAndLoad(String token) {
    VerifiedQrClaims claims = ticketQrPayloadVerifier.verify(token);

    Ticket ticket =
        ticketRepository
            .findById(claims.ticketId())
            .orElseThrow(() -> new BusinessException(ErrorStatus.TICKET_NOT_FOUND));

    // 취소는 PaymentCanceledEventListener가 로컬 ticketStatus=CANCELED로 투영하지만, REFUNDING(환불 진행 중)은
    // 로컬에 반영되지 않는다. 입장은 되돌릴 수 없는 행위이므로 권위 있는 bookingStatus로 최종 판정한다(#364).
    // 환불에 실패한 예매는 booking이 CONFIRMED로 복원되므로(#391) 여기서 자연히 통과한다.
    BookingInfoResponse booking = bookingRestClient.getBooking(ticket.getBookingId());
    if (!CONFIRMED_STATUS.equals(booking.bookingStatus())) {
      throw new BusinessException(ErrorStatus.TICKET_NOT_USABLE);
    }

    if (ticket.getTicketStatus() == TicketStatus.USED) {
      throw new BusinessException(ErrorStatus.TICKET_ALREADY_USED);
    }

    return ticket;
  }
}
