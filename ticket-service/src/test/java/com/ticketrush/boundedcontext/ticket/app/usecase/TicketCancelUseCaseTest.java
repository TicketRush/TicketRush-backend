package com.ticketrush.boundedcontext.ticket.app.usecase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TicketCancelUseCaseTest {

  @InjectMocks private TicketCancelUseCase ticketCancelUseCase;

  @Mock private TicketRepository ticketRepository;

  private static final Long BOOKING_ID = 10L;

  private Ticket ticketWithStatus(TicketStatus status) {
    return Ticket.builder()
        .bookingId(BOOKING_ID)
        .ticketTokenHash("hash")
        .ticketStatus(status)
        .build();
  }

  @Test
  @DisplayName("성공: UNUSED 입장권은 1행이 갱신되어 취소되고, 사유 재조회는 하지 않는다")
  void execute_cancels_unused_ticket() {
    // given: 조건부 UPDATE가 1행을 갱신
    given(
            ticketRepository.markCanceledByBookingId(
                BOOKING_ID, TicketStatus.CANCELED, TicketStatus.UNUSED))
        .willReturn(1);

    // when & then
    assertThatCode(() -> ticketCancelUseCase.execute(BOOKING_ID)).doesNotThrowAnyException();

    // then: 성공 시 사유 구분을 위한 재조회는 일어나지 않는다
    verify(ticketRepository)
        .markCanceledByBookingId(BOOKING_ID, TicketStatus.CANCELED, TicketStatus.UNUSED);
    verify(ticketRepository, never()).findByBookingId(anyLong());
  }

  @Test
  @DisplayName("0행 + 티켓 없음: 예외 없이 경고 로그만 남기고 종료한다")
  void execute_skips_when_ticket_not_found() {
    // given
    given(
            ticketRepository.markCanceledByBookingId(
                BOOKING_ID, TicketStatus.CANCELED, TicketStatus.UNUSED))
        .willReturn(0);
    given(ticketRepository.findByBookingId(BOOKING_ID)).willReturn(Optional.empty());

    // when & then
    assertThatCode(() -> ticketCancelUseCase.execute(BOOKING_ID)).doesNotThrowAnyException();
    verify(ticketRepository).findByBookingId(BOOKING_ID);
  }

  @Test
  @DisplayName("0행 + 이미 USED: 입장 완료 티켓은 덮어쓰지 않고 예외 없이 무시한다")
  void execute_ignores_already_used_ticket() {
    // given
    given(
            ticketRepository.markCanceledByBookingId(
                BOOKING_ID, TicketStatus.CANCELED, TicketStatus.UNUSED))
        .willReturn(0);
    given(ticketRepository.findByBookingId(BOOKING_ID))
        .willReturn(Optional.of(ticketWithStatus(TicketStatus.USED)));

    // when & then: 정책상 USED는 무시(예외 없음)
    assertThatCode(() -> ticketCancelUseCase.execute(BOOKING_ID)).doesNotThrowAnyException();
    verify(ticketRepository).findByBookingId(BOOKING_ID);
  }

  @Test
  @DisplayName("0행 + 이미 CANCELED: 멱등하게 예외 없이 종료한다")
  void execute_is_idempotent_when_already_canceled() {
    // given
    given(
            ticketRepository.markCanceledByBookingId(
                BOOKING_ID, TicketStatus.CANCELED, TicketStatus.UNUSED))
        .willReturn(0);
    given(ticketRepository.findByBookingId(BOOKING_ID))
        .willReturn(Optional.of(ticketWithStatus(TicketStatus.CANCELED)));

    // when & then
    assertThatCode(() -> ticketCancelUseCase.execute(BOOKING_ID)).doesNotThrowAnyException();
    verify(ticketRepository).findByBookingId(BOOKING_ID);
  }

  @Test
  @DisplayName("0행 + 재조회 시 UNUSED(동시성 경합 윈도우): 예외 없이 건너뛴다")
  void execute_skips_when_still_unused_on_recheck() {
    // given: UPDATE 직후 재조회 사이에 다른 트랜잭션이 개입한 드문 경합 케이스
    given(
            ticketRepository.markCanceledByBookingId(
                BOOKING_ID, TicketStatus.CANCELED, TicketStatus.UNUSED))
        .willReturn(0);
    given(ticketRepository.findByBookingId(BOOKING_ID))
        .willReturn(Optional.of(ticketWithStatus(TicketStatus.UNUSED)));

    // when & then: default 분기(예외 미발생, 로그만)
    assertThatCode(() -> ticketCancelUseCase.execute(BOOKING_ID)).doesNotThrowAnyException();
    verify(ticketRepository).findByBookingId(BOOKING_ID);
  }
}
