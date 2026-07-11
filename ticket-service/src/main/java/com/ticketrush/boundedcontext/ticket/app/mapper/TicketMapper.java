package com.ticketrush.boundedcontext.ticket.app.mapper;

import com.ticketrush.boundedcontext.ticket.app.dto.response.EntryVerifyResponse;
import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketQrResponse;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import java.time.LocalDateTime;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TicketMapper {

  // 단일 소스 Entity -> DTO. DB의 id를 DTO의 ticketId로 매핑하고, bookingId/ticketStatus는 동명 자동 매핑한다.
  @Mapping(source = "id", target = "ticketId")
  EntryVerifyResponse toEntryVerifyResponse(Ticket ticket);

  // 다중 소스 Entity + 파라미터 -> DTO. 발급 시각(createdAt)을 issuedAt으로, 상태는 ticket에서 매핑한다.
  // payload/expiresAt 파라미터는 target 필드명과 동일해 자동 매핑된다.
  @Mapping(source = "ticket.ticketStatus", target = "ticketStatus")
  @Mapping(source = "ticket.createdAt", target = "issuedAt")
  TicketQrResponse toTicketQrResponse(Ticket ticket, String payload, LocalDateTime expiresAt);

  // 식별자 + 계산된 토큰 해시 -> Entity. @Builder 대상(bookingId/userId/ticketTokenHash/ticketStatus)만 target이라
  // id/usedAt은 ignore가 불필요하며, 발급 시 상태는 UNUSED로 고정한다.
  @Mapping(target = "ticketStatus", constant = "UNUSED")
  Ticket toEntity(Long bookingId, Long userId, String ticketTokenHash);
}
