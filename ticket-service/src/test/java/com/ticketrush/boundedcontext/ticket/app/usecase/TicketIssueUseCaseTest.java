package com.ticketrush.boundedcontext.ticket.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketIssueResponse;
import com.ticketrush.boundedcontext.ticket.app.support.TicketSaver;
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

  @Mock private TicketSaver ticketSaver;

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
    then(ticketSaver).should().saveInNewTransaction(ticketCaptor.capture());
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
    then(ticketSaver).should(never()).saveInNewTransaction(any());
  }

  @Test
  @DisplayName("성공: 동시 발급으로 booking_id 제약이 충돌하면 이미 발급된 것으로 처리한다")
  void execute_handles_booking_id_race_as_already_issued() {
    // given
    Long bookingId = 1L;
    String token = "raw-ticket-token";
    String tokenHash = "5ef9a9d540243d7534e3d322d14817b1290f0f2f7f57feafe57f5e5d1f13b450";
    // 선조회 시점엔 없었지만(false), 충돌 후 재조회 시 다른 트랜잭션이 먼저 발급(true)
    given(ticketRepository.existsByBookingId(bookingId)).willReturn(false, true);
    given(ticketTokenGenerator.generate()).willReturn(token);
    given(ticketTokenHasher.hash(token)).willReturn(tokenHash);
    willThrow(new DataIntegrityViolationException("duplicate key"))
        .given(ticketSaver)
        .saveInNewTransaction(any(Ticket.class));

    // when
    TicketIssueResponse response = ticketIssueUseCase.execute(bookingId);

    // then
    assertThat(response.issued()).isFalse();
    assertThat(response.token()).isNull();
  }

  @Test
  @DisplayName("실패: 토큰 해시 제약이 충돌하면 재시도를 위해 예외를 그대로 던진다")
  void execute_rethrows_when_token_hash_conflicts() {
    // given
    Long bookingId = 1L;
    String token = "raw-ticket-token";
    String tokenHash = "5ef9a9d540243d7534e3d322d14817b1290f0f2f7f57feafe57f5e5d1f13b450";
    // 선조회/재조회 모두 false → booking_id 충돌이 아니라 token_hash 충돌임을 의미
    given(ticketRepository.existsByBookingId(bookingId)).willReturn(false, false);
    given(ticketTokenGenerator.generate()).willReturn(token);
    given(ticketTokenHasher.hash(token)).willReturn(tokenHash);
    willThrow(new DataIntegrityViolationException("duplicate key"))
        .given(ticketSaver)
        .saveInNewTransaction(any(Ticket.class));

    // when & then
    assertThatThrownBy(() -> ticketIssueUseCase.execute(bookingId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
