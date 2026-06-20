package com.ticketrush.boundedcontext.ticket.domain.entity;

import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ticket")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "id", column = @Column(name = "ticket_id"))
public class Ticket extends AutoIdBaseEntity {

  @Column(name = "booking_id", nullable = false, unique = true)
  private Long bookingId;

  @Column(name = "ticket_token_hash", length = 64, nullable = false, unique = true)
  private String ticketTokenHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "ticket_status", length = 20, nullable = false)
  private TicketStatus ticketStatus;

  @Column(name = "used_at")
  private LocalDateTime usedAt;

  @Builder
  public Ticket(Long bookingId, String ticketTokenHash, TicketStatus ticketStatus) {
    this.bookingId = bookingId;
    this.ticketTokenHash = ticketTokenHash;
    this.ticketStatus = ticketStatus;
  }
}
