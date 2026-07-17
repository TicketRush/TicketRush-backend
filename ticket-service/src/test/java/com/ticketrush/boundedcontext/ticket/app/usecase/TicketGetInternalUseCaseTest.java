package com.ticketrush.boundedcontext.ticket.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketInternalResponse;
import com.ticketrush.boundedcontext.ticket.app.mapper.TicketMapper;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TicketGetInternalUseCaseTest {

  private static final Long BOOKING_ID = 100L;

  @InjectMocks private TicketGetInternalUseCase ticketGetInternalUseCase;

  @Mock private TicketRepository ticketRepository;
  @Mock private TicketMapper ticketMapper;

  private Ticket ticketWithStatus(TicketStatus status) {
    return Ticket.builder()
        .bookingId(BOOKING_ID)
        .userId(10L)
        .ticketTokenHash("hash")
        .ticketStatus(status)
        .build();
  }

  @Test
  @DisplayName("성공: 입장 완료(USED) 입장권의 상태를 반환한다")
  void execute_returns_used_status() {
    // given
    Ticket ticket = ticketWithStatus(TicketStatus.USED);
    given(ticketRepository.findByBookingId(BOOKING_ID)).willReturn(Optional.of(ticket));
    given(ticketMapper.toTicketInternalResponse(ticket))
        .willReturn(new TicketInternalResponse(BOOKING_ID, TicketStatus.USED));

    // when
    TicketInternalResponse response = ticketGetInternalUseCase.execute(BOOKING_ID);

    // then
    assertThat(response.bookingId()).isEqualTo(BOOKING_ID);
    assertThat(response.ticketStatus()).isEqualTo(TicketStatus.USED);
  }

  @Test
  @DisplayName("실패: 발급된 입장권이 없으면 TICKET_NOT_FOUND — 호출 측이 미입장으로 해석한다")
  void execute_throws_when_ticket_not_found() {
    // given
    given(ticketRepository.findByBookingId(BOOKING_ID)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> ticketGetInternalUseCase.execute(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_FOUND);
  }
}
