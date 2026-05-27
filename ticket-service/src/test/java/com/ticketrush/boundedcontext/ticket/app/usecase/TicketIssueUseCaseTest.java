package com.ticketrush.boundedcontext.ticket.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketIssueResponse;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketTokenGenerator;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketTokenHasher;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class TicketIssueUseCaseTest {

  @InjectMocks private TicketIssueUseCase ticketIssueUseCase;

  @Mock private TicketRepository ticketRepository;

  @Mock private TicketTokenGenerator ticketTokenGenerator;

  @Mock private TicketTokenHasher ticketTokenHasher;

  @Test
  @DisplayName("성공: 예매 ID 기준으로 티켓 토큰 해시와 UNUSED 상태를 저장한다")
  void execute_issues_ticket() {
    // given
    Long bookingId = 1L;
    String token = "raw-ticket-token";
    String tokenHash = "5ef9a9d540243d7534e3d322d14817b1290f0f2f7f57feafe57f5e5d1f13b450";
    given(ticketRepository.existsByBookingId(bookingId)).willReturn(false);
    given(ticketTokenGenerator.generate()).willReturn(token);
    given(ticketTokenHasher.hash(token)).willReturn(tokenHash);

    // when
    TicketIssueResponse response = ticketIssueUseCase.execute(bookingId);

    // then
    ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
    then(ticketRepository).should().saveAndFlush(ticketCaptor.capture());
    Ticket savedTicket = ticketCaptor.getValue();
    assertThat(response.issued()).isTrue();
    assertThat(response.token()).isEqualTo(token);
    assertThat(savedTicket.getBookingId()).isEqualTo(bookingId);
    assertThat(savedTicket.getTicketTokenHash()).isEqualTo(tokenHash);
    assertThat(savedTicket.getTicketTokenHash()).doesNotContain("raw-ticket-token");
    assertThat(savedTicket.getTicketStatus()).isEqualTo(TicketStatus.UNUSED);
    assertThat(savedTicket.getUsedAt()).isNull();
  }

  @Test
  @DisplayName("성공: 동일 예매에 이미 티켓이 있으면 토큰을 중복 생성하지 않는다")
  void execute_does_not_issue_duplicate_ticket() {
    // given
    Long bookingId = 1L;
    given(ticketRepository.existsByBookingId(bookingId)).willReturn(true);

    // when
    TicketIssueResponse response = ticketIssueUseCase.execute(bookingId);

    // then
    assertThat(response.issued()).isFalse();
    assertThat(response.token()).isNull();
    then(ticketTokenGenerator).should(never()).generate();
    then(ticketTokenHasher).shouldHaveNoInteractions();
    then(ticketRepository).should(never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("성공: 동시 발급으로 unique 제약이 충돌하면 이미 발급된 것으로 처리한다")
  void execute_handles_duplicate_key_race_as_already_issued() {
    // given
    Long bookingId = 1L;
    String token = "raw-ticket-token";
    String tokenHash = "5ef9a9d540243d7534e3d322d14817b1290f0f2f7f57feafe57f5e5d1f13b450";
    given(ticketRepository.existsByBookingId(bookingId)).willReturn(false);
    given(ticketTokenGenerator.generate()).willReturn(token);
    given(ticketTokenHasher.hash(token)).willReturn(tokenHash);
    given(ticketRepository.saveAndFlush(any(Ticket.class)))
        .willThrow(new DataIntegrityViolationException("duplicate key"));

    // when
    TicketIssueResponse response = ticketIssueUseCase.execute(bookingId);

    // then
    assertThat(response.issued()).isFalse();
    assertThat(response.token()).isNull();
  }
}
