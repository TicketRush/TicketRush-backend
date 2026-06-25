package com.ticketrush.boundedcontext.ticket.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.ticketrush.boundedcontext.ticket.app.dto.response.EntryCheckInResponse;
import com.ticketrush.boundedcontext.ticket.app.support.TicketCheckInProcessor;
import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EntryCheckInUseCaseTest {

  private static final String TOKEN = "qr-token";

  @InjectMocks private EntryCheckInUseCase entryCheckInUseCase;

  @Mock private EntryVerifyUseCase entryVerifyUseCase;

  @Mock private TicketCheckInProcessor ticketCheckInProcessor;

  private Ticket ticket(Long id) {
    Ticket ticket =
        Ticket.builder()
            .bookingId(100L)
            .ticketTokenHash("hash")
            .ticketStatus(TicketStatus.UNUSED)
            .build();
    ReflectionTestUtils.setField(ticket, "id", id);
    return ticket;
  }

  @Test
  @DisplayName("성공: 사전 검증 통과 입장권의 ID로 마킹 처리에 위임하고 결과를 반환한다")
  void execute_delegates_to_processor() {
    // given
    given(entryVerifyUseCase.verifyAndLoad(TOKEN)).willReturn(ticket(1L));
    EntryCheckInResponse expected =
        EntryCheckInResponse.of(1L, LocalDateTime.of(2026, 6, 26, 19, 30, 0));
    given(ticketCheckInProcessor.markUsed(1L)).willReturn(expected);

    // when
    EntryCheckInResponse response = entryCheckInUseCase.execute(TOKEN);

    // then
    assertThat(response).isEqualTo(expected);
  }

  @Test
  @DisplayName("실패: 사전 검증에서 던진 예외는 그대로 전파하고 마킹을 시도하지 않는다")
  void execute_propagates_verification_error() {
    // given
    given(entryVerifyUseCase.verifyAndLoad(TOKEN))
        .willThrow(new BusinessException(ErrorStatus.TICKET_QR_EXPIRED));

    // when & then
    assertThatThrownBy(() -> entryCheckInUseCase.execute(TOKEN))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_QR_EXPIRED);
    then(ticketCheckInProcessor).should(never()).markUsed(any());
  }
}
