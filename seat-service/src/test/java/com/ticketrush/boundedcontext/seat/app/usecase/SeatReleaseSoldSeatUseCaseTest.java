package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.seat.app.support.SeatEventSource;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.config.SeatRefundReleaseProperties;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.types.SeatStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatReleaseSoldSeatUseCaseTest {

  @InjectMocks private SeatReleaseSoldSeatUseCase seatReleaseSoldSeatUseCase;

  @Mock private SeatRepository seatRepository;
  @Mock private SeatStatusEventPublisher seatStatusEventPublisher;

  @Spy
  private SeatRefundReleaseProperties refundReleaseProperties = new SeatRefundReleaseProperties();

  @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

  private static final Long SEAT_ID = 1L;
  private static final String BOOKING_NUMBER = "BOOK-1234";
  private static final String OTHER_BOOKING_NUMBER = "BOOK-OTHER";

  private Seat seatWithStatus(SeatStatus status) {
    return seatSoldTo(status, status == SeatStatus.AVAILABLE ? null : BOOKING_NUMBER);
  }

  private Seat seatSoldTo(SeatStatus status, String bookingNumber) {
    return Seat.builder()
        .performanceId(1L)
        .seatNumber("A-1")
        .seatStatus(status)
        .bookingNumber(bookingNumber)
        .build();
  }

  private double skipCount(String reason) {
    Counter counter =
        meterRegistry
            .find(MetricNames.SEAT_REFUND_RELEASE_SKIPPED)
            .tag(MetricNames.TAG_REASON, reason)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  @DisplayName("성공: 예매 번호가 일치하는 SOLD 좌석을 AVAILABLE로 반환하고 상태 변경 이벤트를 발행한다")
  void execute_success_when_sold_and_booking_number_matches() {
    // given
    Seat seat = seatWithStatus(SeatStatus.SOLD);
    given(seatRepository.findById(SEAT_ID)).willReturn(Optional.of(seat));

    // when
    seatReleaseSoldSeatUseCase.execute(SEAT_ID, BOOKING_NUMBER);

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    verify(seatStatusEventPublisher).publishAfterCommit(seat, SeatEventSource.REFUND_RELEASE);
  }

  @Test
  @DisplayName("재현(#608, 가드 ON): 다른 예매가 결제 완료한 SOLD 좌석은 예매 번호 없는 이벤트로 반환되지 않는다")
  void execute_skip_when_booking_number_missing_and_seat_owned_by_others() {
    // given: 이슈 #608 시나리오 — A의 예매가 좌석을 놓친 뒤 B가 그 좌석을 사서 SOLD가 됐고,
    // 그 상태에서 A가 결제 취소를 누르면 예매 번호 없는 반환 이벤트가 B의 좌석에 도착한다.
    //
    // 주의: 이 테스트는 가드를 켠 구성이다. 배포 기본값(false)에서 이 시나리오를 실제로 막는 것은
    // payment 가 예매 번호를 채우는 쪽이고, seat 에서는 아래 mismatch 테스트가 그 방어를 고정한다.
    refundReleaseProperties.setRequireBookingNumber(true);
    Seat seat = seatSoldTo(SeatStatus.SOLD, OTHER_BOOKING_NUMBER);
    given(seatRepository.findById(SEAT_ID)).willReturn(Optional.of(seat));

    // when
    seatReleaseSoldSeatUseCase.execute(SEAT_ID, null);

    // then: B의 좌석은 그대로 SOLD 로 남아야 한다.
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.SOLD);
    verifyNoInteractions(seatStatusEventPublisher);
    assertThat(skipCount("booking_number_missing")).isEqualTo(1);
  }

  @ParameterizedTest(name = "예매 번호가 [{0}] 이면 반환하지 않는다")
  @NullSource
  @ValueSource(strings = {"", "   "})
  @DisplayName("가드가 켜져 있으면 예매 번호가 빈 반환 요청을 거부한다 (#608)")
  void execute_skip_when_booking_number_blank(String blankBookingNumber) {
    // given
    refundReleaseProperties.setRequireBookingNumber(true);
    Seat seat = seatWithStatus(SeatStatus.SOLD);
    given(seatRepository.findById(SEAT_ID)).willReturn(Optional.of(seat));

    // when
    seatReleaseSoldSeatUseCase.execute(SEAT_ID, blankBookingNumber);

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.SOLD);
    verifyNoInteractions(seatStatusEventPublisher);
    assertThat(skipCount("booking_number_missing")).isEqualTo(1);
  }

  @Test
  @DisplayName("배포 창: 가드가 꺼져 있으면 예매 번호가 없어도 종전대로 반환하되 집계는 남긴다 (#608)")
  void execute_release_when_guard_disabled_but_still_counted() {
    // given: CD 일괄 배포로 구버전 payment 의 이벤트가 도착할 수 있는 구간. 여기서 거부하면 정상 취소의
    // 좌석까지 SOLD 로 고착되므로 종전 동작을 유지한다. 다만 스위치를 켜도 되는지 판단하려면 집계는 필요하다.
    Seat seat = seatWithStatus(SeatStatus.SOLD);
    given(seatRepository.findById(SEAT_ID)).willReturn(Optional.of(seat));

    // when
    seatReleaseSoldSeatUseCase.execute(SEAT_ID, null);

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    verify(seatStatusEventPublisher).publishAfterCommit(seat, SeatEventSource.REFUND_RELEASE);
    assertThat(skipCount("booking_number_missing")).isEqualTo(1);
  }

  @Test
  @DisplayName("ABA 방지(#608 기본 방어): 좌석의 예매 번호가 이벤트와 다르면 반환하지 않는다")
  void execute_skip_when_booking_number_mismatch() {
    // given: 다른 예매가 선점·결제한 좌석을 과거 환불 이벤트가 반환하려는 상황.
    // payment 가 예매 번호를 채우게 된 뒤(#608) 결제 취소 API 경로가 실제로 도달하는 분기가 여기다.
    // 킬 스위치 밖이라 배포 즉시 발화한다.
    Seat seat = seatWithStatus(SeatStatus.SOLD);
    given(seatRepository.findById(SEAT_ID)).willReturn(Optional.of(seat));

    // when
    seatReleaseSoldSeatUseCase.execute(SEAT_ID, "BOOK-9999");

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.SOLD);
    verifyNoInteractions(seatStatusEventPublisher);
    // 정상 방어(mismatch)와 계약 파기(missing)는 다른 축으로 세야 조사 대상이 노이즈에 묻히지 않는다.
    assertThat(skipCount("booking_number_mismatch")).isEqualTo(1);
    assertThat(skipCount("booking_number_missing")).isZero();
  }

  @Test
  @DisplayName("멱등: 이미 AVAILABLE 좌석이면 스킵한다")
  void execute_skip_when_already_available() {
    // given
    Seat seat = seatWithStatus(SeatStatus.AVAILABLE);
    given(seatRepository.findById(SEAT_ID)).willReturn(Optional.of(seat));

    // when
    seatReleaseSoldSeatUseCase.execute(SEAT_ID, BOOKING_NUMBER);

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    verifyNoInteractions(seatStatusEventPublisher);
  }

  @Test
  @DisplayName("안전: HOLD(진행 중 선점) 좌석은 환불 대상이 아니므로 반환하지 않는다")
  void execute_skip_when_hold() {
    // given: 좌석 id 재사용으로 다른 예매가 선점 중일 수 있어, 환불이 활성 HOLD를 깨지 않아야 한다
    Seat seat = seatWithStatus(SeatStatus.HOLD);
    given(seatRepository.findById(SEAT_ID)).willReturn(Optional.of(seat));

    // when
    seatReleaseSoldSeatUseCase.execute(SEAT_ID, BOOKING_NUMBER);

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.HOLD);
    verifyNoInteractions(seatStatusEventPublisher);
    assertThat(skipCount("not_sold")).isEqualTo(1);
  }

  @Test
  @DisplayName("멱등: 좌석이 없으면 예외 없이 스킵한다")
  void execute_skip_when_seat_not_found() {
    // given
    given(seatRepository.findById(SEAT_ID)).willReturn(Optional.empty());

    // when
    seatReleaseSoldSeatUseCase.execute(SEAT_ID, BOOKING_NUMBER);

    // then
    verifyNoInteractions(seatStatusEventPublisher);
    assertThat(skipCount("seat_not_found")).isEqualTo(1);
  }
}
