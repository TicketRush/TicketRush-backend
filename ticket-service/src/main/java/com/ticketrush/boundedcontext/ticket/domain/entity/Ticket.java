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

  /** PaymentConfirmedEvent에서 복제한 예매자. QR 조회의 소유권 확인을 booking 동기 호출 없이 로컬에서 수행하기 위함(#364). */
  @Column(name = "user_id")
  private Long userId;

  @Column(name = "ticket_token_hash", length = 64, nullable = false, unique = true)
  private String ticketTokenHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "ticket_status", length = 20, nullable = false)
  private TicketStatus ticketStatus;

  @Column(name = "used_at")
  private LocalDateTime usedAt;

  @Builder
  public Ticket(Long bookingId, Long userId, String ticketTokenHash, TicketStatus ticketStatus) {
    this.bookingId = bookingId;
    this.userId = userId;
    this.ticketTokenHash = ticketTokenHash;
    this.ticketStatus = ticketStatus;
  }
}
