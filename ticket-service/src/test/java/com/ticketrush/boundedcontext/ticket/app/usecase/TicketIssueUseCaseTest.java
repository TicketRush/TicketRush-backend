package com.ticketrush.boundedcontext.ticket.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketIssueResponse;
import com.ticketrush.boundedcontext.ticket.app.mapper.TicketMapper;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketTokenGenerator;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketTokenHasher;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import com.ticketrush.global.constants.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class TicketIssueUseCaseTest {

  @InjectMocks private TicketIssueUseCase ticketIssueUseCase;

  @Mock private TicketRepository ticketRepository;

  @Mock private TicketTokenGenerator ticketTokenGenerator;

  @Mock private TicketTokenHasher ticketTokenHasher;

  @Spy private TicketMapper ticketMapper = Mappers.getMapper(TicketMapper.class);

  @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Test
  @DisplayName("성공: 예매 ID 기준으로 예매자(userId)·티켓 토큰 해시와 UNUSED 상태를 저장한다")
  void execute_issues_ticket() {
    // given
    Long bookingId = 1L;
    Long userId = 42L;
    String token = "raw-ticket-token";
    String tokenHash = "5ef9a9d540243d7534e3d322d14817b1290f0f2f7f57feafe57f5e5d1f13b450";
    given(ticketRepository.existsByBookingId(bookingId)).willReturn(false);
    given(ticketTokenGenerator.generate()).willReturn(token);
    given(ticketTokenHasher.hash(token)).willReturn(tokenHash);

    // when
    TicketIssueResponse response = ticketIssueUseCase.execute(bookingId, userId);

    // then
    ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
    then(ticketRepository).should().saveAndFlush(ticketCaptor.capture());
    Ticket savedTicket = ticketCaptor.getValue();
    assertThat(response.issued()).isTrue();
    assertThat(response.token()).isEqualTo(token);
    assertThat(savedTicket.getBookingId()).isEqualTo(bookingId);
    // QR 조회가 booking 동기 호출 없이 소유권을 확인하려면 발급 시점에 userId가 복제돼야 한다(#364).
    assertThat(savedTicket.getUserId()).isEqualTo(userId);
    assertThat(savedTicket.getTicketTokenHash()).isEqualTo(tokenHash);
    assertThat(savedTicket.getTicketTokenHash()).doesNotContain("raw-ticket-token");
    assertThat(savedTicket.getTicketStatus()).isEqualTo(TicketStatus.UNUSED);
    assertThat(savedTicket.getUsedAt()).isNull();
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.TICKET_ISSUE, MetricNames.TAG_RESULT, MetricNames.RESULT_ISSUED)
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("성공: 동일 예매에 이미 티켓이 있으면 토큰을 중복 생성하지 않는다")
  void execute_does_not_issue_duplicate_ticket() {
    // given
    Long bookingId = 1L;
    given(ticketRepository.existsByBookingId(bookingId)).willReturn(true);

    // when
    TicketIssueResponse response = ticketIssueUseCase.execute(bookingId, 42L);

    // then
    assertThat(response.issued()).isFalse();
    assertThat(response.token()).isNull();
    then(ticketTokenGenerator).should(never()).generate();
    then(ticketTokenHasher).shouldHaveNoInteractions();
    then(ticketRepository).should(never()).saveAndFlush(any());
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.TICKET_ISSUE,
                    MetricNames.TAG_RESULT,
                    MetricNames.RESULT_ALREADY_ISSUED)
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("실패: unique 제약이 충돌하면 예외를 그대로 전파해 트랜잭션을 롤백시킨다(Inbox 미기록→재소비로 self-heal)")
  void execute_rethrows_on_unique_conflict() {
    // given: 선조회에선 없었지만 저장 시 unique(booking_id 또는 ticket_token_hash) 충돌
    Long bookingId = 1L;
    String token = "raw-ticket-token";
    String tokenHash = "5ef9a9d540243d7534e3d322d14817b1290f0f2f7f57feafe57f5e5d1f13b450";
    given(ticketRepository.existsByBookingId(bookingId)).willReturn(false);
    given(ticketTokenGenerator.generate()).willReturn(token);
    given(ticketTokenHasher.hash(token)).willReturn(tokenHash);
    willThrow(new DataIntegrityViolationException("duplicate key"))
        .given(ticketRepository)
        .saveAndFlush(any(Ticket.class));

    // when & then: DIVE는 그대로 전파된다(#269상 일시 실패로 분류→재소비). 삼키지 않는다.
    assertThatThrownBy(() -> ticketIssueUseCase.execute(bookingId, 42L))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
