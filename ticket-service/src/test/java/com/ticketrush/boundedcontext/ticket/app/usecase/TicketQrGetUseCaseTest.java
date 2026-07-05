package com.ticketrush.boundedcontext.ticket.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketQrResponse;
import com.ticketrush.boundedcontext.ticket.app.mapper.TicketMapper;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.policy.QrPayload;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketQrPayloadGenerator;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.apiclient.BookingRestClient;
import com.ticketrush.boundedcontext.ticket.out.apiclient.dto.BookingInfoResponse;
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

@ExtendWith(MockitoExtension.class)
class TicketQrGetUseCaseTest {

  @InjectMocks private TicketQrGetUseCase ticketQrGetUseCase;

  @Mock private BookingRestClient bookingRestClient;

  @Mock private TicketRepository ticketRepository;

  @Mock private TicketQrPayloadGenerator ticketQrPayloadGenerator;

  @Spy private TicketMapper ticketMapper = Mappers.getMapper(TicketMapper.class);

  private Ticket ticket(Long bookingId, LocalDateTime createdAt) {
    Ticket ticket =
        Ticket.builder()
            .bookingId(bookingId)
            .ticketTokenHash("hash")
            .ticketStatus(TicketStatus.UNUSED)
            .build();
    ReflectionTestUtils.setField(ticket, "id", 1L);
    ReflectionTestUtils.setField(ticket, "createdAt", createdAt);
    return ticket;
  }

  @Test
  @DisplayName("성공: 본인 확정 예매면 QR payload와 메타데이터를 반환한다")
  void execute_returns_qr_payload() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    LocalDateTime issuedAt = LocalDateTime.of(2026, 6, 25, 10, 0);
    LocalDateTime expiresAt = issuedAt.plusMinutes(5);
    given(bookingRestClient.getBooking(bookingId))
        .willReturn(new BookingInfoResponse(bookingId, userId, "CONFIRMED"));
    Ticket ticket = ticket(bookingId, issuedAt);
    given(ticketRepository.findByBookingId(bookingId)).willReturn(Optional.of(ticket));
    given(ticketQrPayloadGenerator.generate(ticket))
        .willReturn(new QrPayload("jwt-payload", expiresAt));

    // when
    TicketQrResponse response = ticketQrGetUseCase.execute(userId, bookingId);

    // then
    assertThat(response.payload()).isEqualTo("jwt-payload");
    assertThat(response.ticketStatus()).isEqualTo(TicketStatus.UNUSED);
    assertThat(response.issuedAt()).isEqualTo(issuedAt);
    assertThat(response.expiresAt()).isEqualTo(expiresAt);
  }

  @Test
  @DisplayName("실패: 본인 예매가 아니면 미존재와 동일한 TICKET_NOT_FOUND로 통일한다")
  void execute_fails_when_not_owner() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    given(bookingRestClient.getBooking(bookingId))
        .willReturn(new BookingInfoResponse(bookingId, 999L, "CONFIRMED"));

    // when & then
    assertThatThrownBy(() -> ticketQrGetUseCase.execute(userId, bookingId))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_FOUND);
    then(ticketRepository).should(never()).findByBookingId(bookingId);
  }

  @Test
  @DisplayName("실패: 미확정 예매는 TICKET_NOT_USABLE로 조회를 막는다")
  void execute_fails_when_pending() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    given(bookingRestClient.getBooking(bookingId))
        .willReturn(new BookingInfoResponse(bookingId, userId, "PENDING"));

    // when & then
    assertThatThrownBy(() -> ticketQrGetUseCase.execute(userId, bookingId))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_USABLE);
    then(ticketRepository).should(never()).findByBookingId(bookingId);
  }

  @Test
  @DisplayName("실패: 취소된 예매는 TICKET_NOT_USABLE로 조회를 막는다")
  void execute_fails_when_canceled() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    given(bookingRestClient.getBooking(bookingId))
        .willReturn(new BookingInfoResponse(bookingId, userId, "CANCELED"));

    // when & then
    assertThatThrownBy(() -> ticketQrGetUseCase.execute(userId, bookingId))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_USABLE);
  }

  @Test
  @DisplayName("실패: 확정 예매라도 발급된 티켓이 없으면 TICKET_NOT_FOUND를 던진다")
  void execute_fails_when_ticket_not_issued() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    given(bookingRestClient.getBooking(bookingId))
        .willReturn(new BookingInfoResponse(bookingId, userId, "CONFIRMED"));
    given(ticketRepository.findByBookingId(bookingId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> ticketQrGetUseCase.execute(userId, bookingId))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_FOUND);
  }
}
