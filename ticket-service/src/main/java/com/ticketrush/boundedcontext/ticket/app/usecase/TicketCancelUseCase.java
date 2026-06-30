package com.ticketrush.boundedcontext.ticket.app.usecase;

import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 취소(환불)에 따라 발급된 입장권을 UNUSED -> CANCELED로 전이시킨다.
 *
 * <p>조건부 UPDATE의 영향행수로 결과를 판정한다. 1행이면 취소 성공, 0행이면 전이 대상이 아니므로(없음/이미 USED/이미 CANCELED) 사유를 구분해 로그만
 * 남기고 예외를 던지지 않는다. 결제 취소는 이미 확정된 사건이라 재시도해도 결과가 바뀌지 않으므로, 호출 측(이벤트 리스너)의 무한 재시도/파티션 블로킹을 유발하지 않게
 * 한다.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class TicketCancelUseCase {

  private final TicketRepository ticketRepository;

  public void execute(Long bookingId) {
    int updatedCount =
        ticketRepository.markCanceledByBookingId(
            bookingId, TicketStatus.CANCELED, TicketStatus.UNUSED);

    if (updatedCount == 1) {
      log.info("결제 취소로 입장권을 취소 처리했습니다. bookingId: {}", bookingId);
      return;
    }

    // 0행: UNUSED가 아니어서 전이되지 않음. 사유를 구분해 로그만 남긴다(예외 전파 금지).
    Ticket current = ticketRepository.findByBookingId(bookingId).orElse(null);

    if (current == null) {
      log.warn("취소 대상 입장권이 없어 건너뜁니다. bookingId: {}", bookingId);
      return;
    }

    switch (current.getTicketStatus()) {
      case USED -> log.warn("이미 사용(입장 완료)된 입장권이라 취소를 무시합니다. bookingId: {}", bookingId);
      case CANCELED -> log.info("이미 취소된 입장권입니다(멱등 처리). bookingId: {}", bookingId);
      default ->
          log.warn(
              "예상치 못한 입장권 상태라 취소를 건너뜁니다. bookingId: {}, status: {}",
              bookingId,
              current.getTicketStatus());
    }
  }
}
