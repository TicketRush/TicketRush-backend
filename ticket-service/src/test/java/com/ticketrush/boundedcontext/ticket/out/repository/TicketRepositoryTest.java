package com.ticketrush.boundedcontext.ticket.out.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

@DataJpaTest
class TicketRepositoryTest {

  @Autowired private TicketRepository ticketRepository;

  @Autowired private TestEntityManager entityManager;

  private Ticket persistUnused(Long bookingId, String hash) {
    Ticket ticket =
        Ticket.builder()
            .bookingId(bookingId)
            .ticketTokenHash(hash)
            .ticketStatus(TicketStatus.UNUSED)
            .build();
    Ticket saved = entityManager.persist(ticket);
    entityManager.flush();
    entityManager.clear();
    return saved;
  }

  @Test
  @DisplayName("markUsedById: UNUSED 입장권을 USED로 전이하고 usedAt을 기록하며 1행을 갱신한다")
  void markUsedById_changes_unused_to_used() {
    // given
    Ticket ticket = persistUnused(100L, "hash-1");
    LocalDateTime now = LocalDateTime.now();

    // when
    int updatedCount =
        ticketRepository.markUsedById(ticket.getId(), now, TicketStatus.USED, TicketStatus.UNUSED);

    // then
    assertThat(updatedCount).isEqualTo(1);
    entityManager.clear();
    Ticket found = ticketRepository.findById(ticket.getId()).orElseThrow();
    assertThat(found.getTicketStatus()).isEqualTo(TicketStatus.USED);
    assertThat(found.getUsedAt()).isNotNull();
  }

  @Test
  @DisplayName("markUsedById: 이미 USED인 입장권은 0행을 갱신해 중복 입장을 막는다")
  void markUsedById_returns_zero_when_already_used() {
    // given
    Ticket ticket = persistUnused(200L, "hash-2");
    LocalDateTime now = LocalDateTime.now();
    int first =
        ticketRepository.markUsedById(ticket.getId(), now, TicketStatus.USED, TicketStatus.UNUSED);
    assertThat(first).isEqualTo(1);
    entityManager.clear();

    // when: 동일 입장권을 다시 입장 처리 시도
    int second =
        ticketRepository.markUsedById(ticket.getId(), now, TicketStatus.USED, TicketStatus.UNUSED);

    // then
    assertThat(second).isEqualTo(0);
  }

  @Test
  @DisplayName("markCanceledByBookingId: UNUSED 입장권을 CANCELED로 전이하며 1행을 갱신한다")
  void markCanceledByBookingId_changes_unused_to_canceled() {
    // given
    Ticket ticket = persistUnused(300L, "hash-3");

    // when
    int updatedCount =
        ticketRepository.markCanceledByBookingId(300L, TicketStatus.CANCELED, TicketStatus.UNUSED);

    // then
    assertThat(updatedCount).isEqualTo(1);
    entityManager.clear();
    Ticket found = ticketRepository.findById(ticket.getId()).orElseThrow();
    assertThat(found.getTicketStatus()).isEqualTo(TicketStatus.CANCELED);
  }

  @Test
  @DisplayName("markCanceledByBookingId: 이미 USED인 입장권은 0행을 갱신하고 USED 상태를 유지한다")
  void markCanceledByBookingId_returns_zero_when_already_used() {
    // given
    Ticket ticket = persistUnused(400L, "hash-4");
    int used =
        ticketRepository.markUsedById(
            ticket.getId(), LocalDateTime.now(), TicketStatus.USED, TicketStatus.UNUSED);
    assertThat(used).isEqualTo(1);
    entityManager.clear();

    // when
    int updatedCount =
        ticketRepository.markCanceledByBookingId(400L, TicketStatus.CANCELED, TicketStatus.UNUSED);

    // then
    assertThat(updatedCount).isEqualTo(0);
    entityManager.clear();
    Ticket found = ticketRepository.findById(ticket.getId()).orElseThrow();
    assertThat(found.getTicketStatus()).isEqualTo(TicketStatus.USED);
  }

  @Test
  @DisplayName("markCanceledByBookingId: 이미 CANCELED인 입장권은 0행을 갱신해 멱등하게 동작한다")
  void markCanceledByBookingId_returns_zero_when_already_canceled() {
    // given
    persistUnused(500L, "hash-5");
    int first =
        ticketRepository.markCanceledByBookingId(500L, TicketStatus.CANCELED, TicketStatus.UNUSED);
    assertThat(first).isEqualTo(1);
    entityManager.clear();

    // when: 동일 예매에 대해 취소를 다시 시도
    int second =
        ticketRepository.markCanceledByBookingId(500L, TicketStatus.CANCELED, TicketStatus.UNUSED);

    // then
    assertThat(second).isEqualTo(0);
  }
}
