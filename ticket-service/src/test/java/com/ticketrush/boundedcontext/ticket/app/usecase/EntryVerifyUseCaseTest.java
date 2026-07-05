package com.ticketrush.boundedcontext.ticket.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.ticketrush.boundedcontext.ticket.app.dto.response.EntryVerifyResponse;
import com.ticketrush.boundedcontext.ticket.app.mapper.TicketMapper;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.policy.TicketQrPayloadVerifier;
import com.ticketrush.boundedcontext.ticket.domain.policy.VerifiedQrClaims;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.boundedcontext.ticket.out.apiclient.BookingRestClient;
import com.ticketrush.boundedcontext.ticket.out.apiclient.dto.BookingInfoResponse;
import com.ticketrush.boundedcontext.ticket.out.repository.TicketRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
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
class EntryVerifyUseCaseTest {

  private static final String TOKEN = "qr-token";

  @InjectMocks private EntryVerifyUseCase entryVerifyUseCase;

  @Mock private TicketQrPayloadVerifier ticketQrPayloadVerifier;

  @Mock private TicketRepository ticketRepository;

  @Mock private BookingRestClient bookingRestClient;

  @Spy private TicketMapper ticketMapper = Mappers.getMapper(TicketMapper.class);

  private Ticket ticket(Long id, Long bookingId, TicketStatus status) {
    Ticket ticket =
        Ticket.builder().bookingId(bookingId).ticketTokenHash("hash").ticketStatus(status).build();
    ReflectionTestUtils.setField(ticket, "id", id);
    return ticket;
  }

  @Test
  @DisplayName("성공: 확정 예매의 미사용 입장권이면 입장 가능 정보를 반환한다")
  void execute_returns_verify_response() {
    // given
    given(ticketQrPayloadVerifier.verify(TOKEN)).willReturn(new VerifiedQrClaims(1L));
    given(ticketRepository.findById(1L))
        .willReturn(Optional.of(ticket(1L, 100L, TicketStatus.UNUSED)));
    given(bookingRestClient.getBooking(100L))
        .willReturn(new BookingInfoResponse(100L, 10L, "CONFIRMED"));

    // when
    EntryVerifyResponse response = entryVerifyUseCase.execute(TOKEN);

    // then
    assertThat(response.ticketId()).isEqualTo(1L);
    assertThat(response.bookingId()).isEqualTo(100L);
    assertThat(response.ticketStatus()).isEqualTo(TicketStatus.UNUSED);
  }

  @Test
  @DisplayName("실패: QR 서명/형식 오류는 검증기의 예외를 그대로 전파한다")
  void execute_propagates_invalid_qr() {
    // given
    given(ticketQrPayloadVerifier.verify(TOKEN))
        .willThrow(new BusinessException(ErrorStatus.TICKET_QR_INVALID));

    // when & then
    assertThatThrownBy(() -> entryVerifyUseCase.execute(TOKEN))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_QR_INVALID);
    then(ticketRepository).should(never()).findById(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("실패: 입장권이 존재하지 않으면 TICKET_NOT_FOUND를 던진다")
  void execute_fails_when_ticket_not_found() {
    // given
    given(ticketQrPayloadVerifier.verify(TOKEN)).willReturn(new VerifiedQrClaims(1L));
    given(ticketRepository.findById(1L)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> entryVerifyUseCase.execute(TOKEN))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_FOUND);
    then(bookingRestClient).should(never()).getBooking(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("실패: 확정되지 않은(취소 포함) 예매는 TICKET_NOT_USABLE을 던진다")
  void execute_fails_when_not_confirmed() {
    // given
    given(ticketQrPayloadVerifier.verify(TOKEN)).willReturn(new VerifiedQrClaims(1L));
    given(ticketRepository.findById(1L))
        .willReturn(Optional.of(ticket(1L, 100L, TicketStatus.UNUSED)));
    given(bookingRestClient.getBooking(100L))
        .willReturn(new BookingInfoResponse(100L, 10L, "CANCELED"));

    // when & then
    assertThatThrownBy(() -> entryVerifyUseCase.execute(TOKEN))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_USABLE);
  }

  @Test
  @DisplayName("실패: 이미 사용된 입장권은 TICKET_ALREADY_USED를 던진다")
  void execute_fails_when_already_used() {
    // given
    given(ticketQrPayloadVerifier.verify(TOKEN)).willReturn(new VerifiedQrClaims(1L));
    given(ticketRepository.findById(1L))
        .willReturn(Optional.of(ticket(1L, 100L, TicketStatus.USED)));
    given(bookingRestClient.getBooking(100L))
        .willReturn(new BookingInfoResponse(100L, 10L, "CONFIRMED"));

    // when & then
    assertThatThrownBy(() -> entryVerifyUseCase.execute(TOKEN))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_ALREADY_USED);
  }
}
