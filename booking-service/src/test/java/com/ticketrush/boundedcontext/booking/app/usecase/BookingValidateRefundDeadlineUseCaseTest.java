package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.policy.RefundDeadlinePolicy;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.apiclient.PerformanceRestClient;
import com.ticketrush.boundedcontext.booking.out.apiclient.dto.PerformanceInfoResponse;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BookingValidateRefundDeadlineUseCaseTest {

  private static final Long USER_ID = 10L;
  private static final Long BOOKING_ID = 100L;
  private static final Long PERFORMANCE_ID = 2L;
  private static final String BOOKING_NUMBER = "BOOK-1234";
  private static final LocalDate SHOW_DATE = LocalDate.of(2026, 7, 20);
  private static final LocalTime SHOW_TIME = LocalTime.of(19, 30);

  @InjectMocks private BookingValidateRefundDeadlineUseCase bookingValidateRefundDeadlineUseCase;

  @Mock private BookingRepository bookingRepository;
  @Mock private PerformanceRestClient performanceRestClient;
  @Mock private RefundDeadlinePolicy refundDeadlinePolicy;

  private Booking bookingWithStatus(BookingStatus status) {
    Booking booking =
        Booking.builder()
            .bookingNumber(BOOKING_NUMBER)
            .userId(USER_ID)
            .performanceId(PERFORMANCE_ID)
            .seatId(3L)
            .bookingStatus(status)
            .build();
    ReflectionTestUtils.setField(booking, "id", BOOKING_ID); // AutoIdBaseEntity의 ID 강제 주입
    return booking;
  }

  private PerformanceInfoResponse performance(LocalDate showDate, LocalTime showTime) {
    return new PerformanceInfoResponse("공연", showDate, showTime, "서울", 50000L);
  }

  private void givenBooking(BookingStatus status) {
    given(bookingRepository.findByBookingNumberAndUserId(BOOKING_NUMBER, USER_ID))
        .willReturn(Optional.of(bookingWithStatus(status)));
  }

  @Test
  @DisplayName("성공: 공연까지 7일 이상 남은 CONFIRMED 예매는 통과시킨다 (#668)")
  void execute_allowsBeforeDeadline() {
    // given
    givenBooking(BookingStatus.CONFIRMED);
    given(performanceRestClient.getPerformance(PERFORMANCE_ID))
        .willReturn(Optional.of(performance(SHOW_DATE, SHOW_TIME)));
    given(refundDeadlinePolicy.allowsRefund(SHOW_DATE, SHOW_TIME)).willReturn(true);

    // when & then
    assertThatCode(() -> bookingValidateRefundDeadlineUseCase.execute(USER_ID, BOOKING_NUMBER))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("실패: 환불 마감이 지났으면 409로 거절한다 (#668)")
  void execute_rejectsAfterDeadline() {
    // given
    givenBooking(BookingStatus.CONFIRMED);
    given(performanceRestClient.getPerformance(PERFORMANCE_ID))
        .willReturn(Optional.of(performance(SHOW_DATE, SHOW_TIME)));
    given(refundDeadlinePolicy.allowsRefund(SHOW_DATE, SHOW_TIME)).willReturn(false);

    // when & then
    assertThatThrownBy(() -> bookingValidateRefundDeadlineUseCase.execute(USER_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.BOOKING_REFUND_DEADLINE_PASSED);
  }

  @Test
  @DisplayName("성공: PENDING 즉시 취소는 공연일과 무관하게 허용한다 (#668)")
  void execute_skipsPending() {
    // given
    givenBooking(BookingStatus.PENDING);

    // when
    bookingValidateRefundDeadlineUseCase.execute(USER_ID, BOOKING_NUMBER);

    // then — 결제 전 이탈은 환불이 아니므로 공연 조회 왕복조차 일어나선 안 된다.
    verifyNoInteractions(performanceRestClient, refundDeadlinePolicy);
  }

  @Test
  @DisplayName("실패: REFUNDING도 마감을 검사해 복원 창을 닫는다 (#668)")
  void execute_checksRefundingToCloseRestoreWindow() {
    // given — recordRefundFailure(#391)가 REFUNDING을 CONFIRMED로 되돌리므로, 여기서 그냥 통과시키면
    // 가드 읽기와 취소 트랜잭션 읽기 사이의 창에서 마감을 한 번도 받지 않은 환불이 개시된다.
    givenBooking(BookingStatus.REFUNDING);
    given(performanceRestClient.getPerformance(PERFORMANCE_ID))
        .willReturn(Optional.of(performance(SHOW_DATE, SHOW_TIME)));
    given(refundDeadlinePolicy.allowsRefund(SHOW_DATE, SHOW_TIME)).willReturn(false);

    // when & then
    assertThatThrownBy(() -> bookingValidateRefundDeadlineUseCase.execute(USER_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.BOOKING_REFUND_DEADLINE_PASSED);
  }

  @Test
  @DisplayName("성공: CANCELED처럼 환불이 성사될 수 없는 상태는 공연을 조회하지 않는다 (#668)")
  void execute_skipsTerminalStatus() {
    // given — CONFIRMED로 돌아오는 경로가 없어 불필요한 왜복을 하지 않는다.
    givenBooking(BookingStatus.CANCELED);

    // when
    bookingValidateRefundDeadlineUseCase.execute(USER_ID, BOOKING_NUMBER);

    // then
    verifyNoInteractions(performanceRestClient, refundDeadlinePolicy);
  }

  @Test
  @DisplayName("실패: 공연 조회에 실패하면 환불을 차단한다(fail-closed) (#668)")
  void execute_failsClosedWhenPerformanceLookupFails() {
    // given — PerformanceRestClient는 부분 응답 정책(#560) 때문에 실패를 Optional.empty()로 수렴시킨다.
    givenBooking(BookingStatus.CONFIRMED);
    given(performanceRestClient.getPerformance(PERFORMANCE_ID)).willReturn(Optional.empty());

    // when & then — 빈 결과를 "제한 없음"으로 읽으면 performance-service 장애가 곧 D-7 무력화가 된다.
    assertThatThrownBy(() -> bookingValidateRefundDeadlineUseCase.execute(USER_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_PERFORMANCE_COMMUNICATION_FAILED);
  }

  @Test
  @DisplayName("실패: 공연 시각이 비어 있으면 환불을 차단한다 (#668)")
  void execute_failsClosedWhenShowTimeMissing() {
    // given — Performance는 showTime이 nullable = false다. 비었다면 스키마·라우팅 이상이므로 낙관하지 않는다.
    givenBooking(BookingStatus.CONFIRMED);
    given(performanceRestClient.getPerformance(PERFORMANCE_ID))
        .willReturn(Optional.of(performance(SHOW_DATE, null)));

    // when & then
    assertThatThrownBy(() -> bookingValidateRefundDeadlineUseCase.execute(USER_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_PERFORMANCE_COMMUNICATION_FAILED);
    verifyNoInteractions(refundDeadlinePolicy);
  }

  @Test
  @DisplayName("실패: 공연일이 비어 있으면 환불을 차단한다 (#668)")
  void execute_failsClosedWhenShowDateMissing() {
    // given
    givenBooking(BookingStatus.CONFIRMED);
    given(performanceRestClient.getPerformance(PERFORMANCE_ID))
        .willReturn(Optional.of(performance(null, SHOW_TIME)));

    // when & then
    assertThatThrownBy(() -> bookingValidateRefundDeadlineUseCase.execute(USER_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_PERFORMANCE_COMMUNICATION_FAILED);
  }

  @Test
  @DisplayName("성공: internal 판정은 마감 전이면 true 를 돌려준다 (#668)")
  void isRefundAllowed_trueBeforeDeadline() {
    // given — 결제 취소 경로(payment)가 이 판정을 그대로 쓴다.
    given(bookingRepository.findById(BOOKING_ID))
        .willReturn(Optional.of(bookingWithStatus(BookingStatus.CONFIRMED)));
    given(performanceRestClient.getPerformance(PERFORMANCE_ID))
        .willReturn(Optional.of(performance(SHOW_DATE, SHOW_TIME)));
    given(refundDeadlinePolicy.allowsRefund(SHOW_DATE, SHOW_TIME)).willReturn(true);

    // when & then
    assertThat(bookingValidateRefundDeadlineUseCase.isRefundAllowed(BOOKING_ID)).isTrue();
  }

  @Test
  @DisplayName("성공: internal 판정은 마감이 지났으면 false 를 돌려준다 (#668)")
  void isRefundAllowed_falseAfterDeadline() {
    // given
    given(bookingRepository.findById(BOOKING_ID))
        .willReturn(Optional.of(bookingWithStatus(BookingStatus.CONFIRMED)));
    given(performanceRestClient.getPerformance(PERFORMANCE_ID))
        .willReturn(Optional.of(performance(SHOW_DATE, SHOW_TIME)));
    given(refundDeadlinePolicy.allowsRefund(SHOW_DATE, SHOW_TIME)).willReturn(false);

    // when & then — 예외가 아니라 값으로 답한다. 거절 코드는 payment 가 자신의 것으로 낸다.
    assertThat(bookingValidateRefundDeadlineUseCase.isRefundAllowed(BOOKING_ID)).isFalse();
  }

  @Test
  @DisplayName("성공: internal 판정은 마감 정책 대상이 아니면 공연 조회 없이 true 다 (#668)")
  void isRefundAllowed_trueWhenNotRefundable() {
    // given — PENDING 은 마감 정책이 막을 대상이 아니다. 상태 자체의 판정은 payment 의 다른 가드가 한다.
    given(bookingRepository.findById(BOOKING_ID))
        .willReturn(Optional.of(bookingWithStatus(BookingStatus.PENDING)));

    // when & then
    assertThat(bookingValidateRefundDeadlineUseCase.isRefundAllowed(BOOKING_ID)).isTrue();
    verifyNoInteractions(performanceRestClient, refundDeadlinePolicy);
  }

  @Test
  @DisplayName("실패: internal 판정도 공연 조회 실패면 false 가 아니라 503 으로 끝난다 (#668)")
  void isRefundAllowed_failsClosedOnLookupFailure() {
    // given — false 를 돌려도 payment 가 막아주지만, 그러면 장애가 "마감 지남"(409)으로 오안내된다.
    given(bookingRepository.findById(BOOKING_ID))
        .willReturn(Optional.of(bookingWithStatus(BookingStatus.CONFIRMED)));
    given(performanceRestClient.getPerformance(PERFORMANCE_ID)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> bookingValidateRefundDeadlineUseCase.isRefundAllowed(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_PERFORMANCE_COMMUNICATION_FAILED);
  }

  @Test
  @DisplayName("실패: 예매가 없거나 소유자가 아니면 404로 거절한다 (#668)")
  void execute_rejectsUnknownBooking() {
    // given — 소유권을 먼저 검증해 비소유자가 타인 예매의 공연 일정을 알아내지 못하게 한다.
    given(bookingRepository.findByBookingNumberAndUserId(BOOKING_NUMBER, USER_ID))
        .willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> bookingValidateRefundDeadlineUseCase.execute(USER_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.BOOKING_NOT_FOUND);
    verifyNoInteractions(performanceRestClient, refundDeadlinePolicy);
  }
}
