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
}
