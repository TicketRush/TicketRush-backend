package com.ticketrush.boundedcontext.ticket.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketQrResponse;
import com.ticketrush.boundedcontext.ticket.app.mapper.TicketMapper;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.policy.QrPayload;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketQrPayloadGenerator;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** QR 조회는 booking-service 동기 호출 없이 로컬 데이터(ticket.userId, ticketStatus)로만 판정한다(#364). */
@ExtendWith(MockitoExtension.class)
class TicketQrGetUseCaseTest {

  private static final Long USER_ID = 10L;
  private static final Long BOOKING_ID = 100L;

  @InjectMocks private TicketQrGetUseCase ticketQrGetUseCase;

  @Mock private TicketRepository ticketRepository;

  @Mock private TicketQrPayloadGenerator ticketQrPayloadGenerator;

  @Spy private TicketMapper ticketMapper = Mappers.getMapper(TicketMapper.class);

  private Ticket ticket(Long userId, TicketStatus status, LocalDateTime createdAt) {
    Ticket ticket =
        Ticket.builder()
            .bookingId(BOOKING_ID)
            .userId(userId)
            .ticketTokenHash("hash")
            .ticketStatus(status)
            .build();
    ReflectionTestUtils.setField(ticket, "id", 1L);
    ReflectionTestUtils.setField(ticket, "createdAt", createdAt);
    return ticket;
  }

  @Test
  @DisplayName("성공: 본인 티켓이면 booking 호출 없이 QR payload와 메타데이터를 반환한다")
  void execute_returns_qr_payload() {
    // given
    LocalDateTime issuedAt = LocalDateTime.of(2026, 6, 25, 10, 0);
    LocalDateTime expiresAt = issuedAt.plusMinutes(5);
    Ticket ticket = ticket(USER_ID, TicketStatus.UNUSED, issuedAt);
    given(ticketRepository.findByBookingId(BOOKING_ID)).willReturn(Optional.of(ticket));
    given(ticketQrPayloadGenerator.generate(ticket))
        .willReturn(new QrPayload("jwt-payload", expiresAt));

    // when
    TicketQrResponse response = ticketQrGetUseCase.execute(USER_ID, BOOKING_ID);

    // then
    assertThat(response.payload()).isEqualTo("jwt-payload");
    assertThat(response.ticketStatus()).isEqualTo(TicketStatus.UNUSED);
    assertThat(response.issuedAt()).isEqualTo(issuedAt);
    assertThat(response.expiresAt()).isEqualTo(expiresAt);
  }

  @Test
  @DisplayName("실패: 발급된 티켓이 없으면 TICKET_NOT_FOUND를 던진다")
  void execute_fails_when_ticket_not_issued() {
    // given
    given(ticketRepository.findByBookingId(BOOKING_ID)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> ticketQrGetUseCase.execute(USER_ID, BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_FOUND);
  }

  @Test
  @DisplayName("실패: 본인 티켓이 아니면 미존재와 동일한 TICKET_NOT_FOUND로 통일한다")
  void execute_fails_when_not_owner() {
    // given
    Ticket ticket = ticket(999L, TicketStatus.UNUSED, LocalDateTime.now());
    given(ticketRepository.findByBookingId(BOOKING_ID)).willReturn(Optional.of(ticket));

    // when & then
    assertThatThrownBy(() -> ticketQrGetUseCase.execute(USER_ID, BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_FOUND);
  }

  @Test
  @DisplayName("실패: backfill 되지 않은 티켓(userId=null)도 소유권 확인에 실패해 TICKET_NOT_FOUND를 던진다")
  void execute_fails_when_user_id_not_backfilled() {
    // given
    Ticket ticket = ticket(null, TicketStatus.UNUSED, LocalDateTime.now());
    given(ticketRepository.findByBookingId(BOOKING_ID)).willReturn(Optional.of(ticket));

    // when & then
    assertThatThrownBy(() -> ticketQrGetUseCase.execute(USER_ID, BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_FOUND);
  }

  @Test
  @DisplayName("실패: 취소된 예매(로컬 ticketStatus=CANCELED)는 TICKET_NOT_USABLE로 조회를 막는다")
  void execute_fails_when_canceled() {
    // given
    Ticket ticket = ticket(USER_ID, TicketStatus.CANCELED, LocalDateTime.now());
    given(ticketRepository.findByBookingId(BOOKING_ID)).willReturn(Optional.of(ticket));

    // when & then
    assertThatThrownBy(() -> ticketQrGetUseCase.execute(USER_ID, BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_USABLE);
  }
}
