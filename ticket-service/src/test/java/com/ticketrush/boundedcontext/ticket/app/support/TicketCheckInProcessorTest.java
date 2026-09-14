package com.ticketrush.boundedcontext.ticket.app.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.ticketrush.boundedcontext.ticket.app.dto.response.EntryCheckInResponse;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@ResourceLock("java.util.TimeZone.default")
class TicketCheckInProcessorTest {

  @InjectMocks private TicketCheckInProcessor ticketCheckInProcessor;

  @Mock private TicketRepository ticketRepository;

  private Ticket ticket(Long id, TicketStatus status) {
    Ticket ticket =
        Ticket.builder().bookingId(100L).ticketTokenHash("hash").ticketStatus(status).build();
    ReflectionTestUtils.setField(ticket, "id", id);
    return ticket;
  }

  @Test
  @DisplayName("성공: 1행 갱신이면 USED/usedAt을 담아 반환하고 재조회하지 않는다")
  void markUsed_succeeds_when_one_row_updated() {
    // given
    given(
            ticketRepository.markUsedById(
                eq(1L), any(), eq(TicketStatus.USED), eq(TicketStatus.UNUSED)))
        .willReturn(1);

    // when
    EntryCheckInResponse response = ticketCheckInProcessor.markUsed(1L);

    // then
    assertThat(response.ticketId()).isEqualTo(1L);
    assertThat(response.ticketStatus()).isEqualTo(TicketStatus.USED);
    assertThat(response.usedAt()).isNotNull();
    then(ticketRepository).should(never()).findById(any());
  }

  @Test
  @DisplayName("실패: 0행 갱신 + 현재 USED면 동시 스캔 패자로 TICKET_ALREADY_USED를 던진다")
  void markUsed_fails_when_lost_race_already_used() {
    // given
    given(
            ticketRepository.markUsedById(
                eq(1L), any(), eq(TicketStatus.USED), eq(TicketStatus.UNUSED)))
        .willReturn(0);
    given(ticketRepository.findById(1L)).willReturn(Optional.of(ticket(1L, TicketStatus.USED)));

    // when & then
    assertThatThrownBy(() -> ticketCheckInProcessor.markUsed(1L))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_ALREADY_USED);
  }

  @Test
  @DisplayName("실패: 0행 갱신 + 입장권이 사라졌으면 TICKET_NOT_FOUND를 던진다")
  void markUsed_fails_when_ticket_disappeared() {
    // given
    given(
            ticketRepository.markUsedById(
                eq(1L), any(), eq(TicketStatus.USED), eq(TicketStatus.UNUSED)))
        .willReturn(0);
    given(ticketRepository.findById(1L)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> ticketCheckInProcessor.markUsed(1L))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_FOUND);
  }

  @Test
  @DisplayName("실패: 0행 갱신 + UNUSED/USED가 아니면 TICKET_NOT_USABLE을 던진다")
  void markUsed_fails_when_not_usable() {
    // given
    given(
            ticketRepository.markUsedById(
                eq(1L), any(), eq(TicketStatus.USED), eq(TicketStatus.UNUSED)))
        .willReturn(0);
    given(ticketRepository.findById(1L)).willReturn(Optional.of(ticket(1L, TicketStatus.CANCELED)));

    // when & then
    assertThatThrownBy(() -> ticketCheckInProcessor.markUsed(1L))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_USABLE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTC", "Asia/Seoul"})
  @DisplayName("입장 저장 시각과 응답 usedAt은 같은 UTC 현재 시각이다")
  void checkInPersistsAndReturnsUtcTime(String zone) {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      assertThat(TimeZone.getDefault().getID()).isEqualTo(zone);
      given(
              ticketRepository.markUsedById(
                  eq(1L), any(), eq(TicketStatus.USED), eq(TicketStatus.UNUSED)))
          .willReturn(1);
      Instant before = Instant.now();
      EntryCheckInResponse response = ticketCheckInProcessor.markUsed(1L);
      Instant after = Instant.now();
      assertThat(response.usedAt().toInstant(ZoneOffset.UTC)).isBetween(before, after);
      then(ticketRepository)
          .should()
          .markUsedById(1L, response.usedAt(), TicketStatus.USED, TicketStatus.UNUSED);
    } finally {
      TimeZone.setDefault(original);
    }
  }
}
