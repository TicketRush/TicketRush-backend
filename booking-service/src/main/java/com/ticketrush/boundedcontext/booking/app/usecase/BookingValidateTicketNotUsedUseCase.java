package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.policy.RefundingStuckPolicy;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.apiclient.TicketRestClient;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 입장을 완료한(ticket = USED) 예매의 <b>환불 개시</b>를 차단한다 (#399).
 *
 * <p>입장 후 환불이 성사되면 {@code TicketCancelUseCase}는 USED 입장권을 전이시키지 않지만 {@code
 * SeatReleaseSoldSeatUseCase}는 티켓 상태를 보지 않고 좌석을 SOLD → AVAILABLE로 반환한다. 공연장에 사람이 앉아 있는 좌석이 재판매되는
 * 것이다. 확정된 정책은 <b>입장한 예매는 환불하지 않는다</b>이므로(ADR 5), 좌석이 반환될 상황을 환불 요청 시점에 없앤다.
 *
 * <p><b>검사 대상은 환불이 성사될 수 있는 예매뿐이다.</b> 환불 개시는 {@code requestRefund()}가 성공할 때만 일어나고 그것은 <b>취소 유스케이스의
 * 트랜잭션 시점</b>에 CONFIRMED일 때뿐이다. 가드는 그 트랜잭션 밖에서 먼저 읽으므로, 두 읽기 사이에 상태가 바뀌는 창이 존재한다 — 실제로 {@code
 * recordRefundFailure}(#391)가 REFUNDING을 CONFIRMED로 되돌린다. 따라서 가드 시점에 <b>CONFIRMED이거나
 * REFUNDING이면</b> 검사한다. CONFIRMED로 복원될 수 있는 상태가 REFUNDING뿐이므로 이 둘만 보면 창이 닫힌다.
 * CANCELED·EXPIRED·REFUNDED·PENDING은 CONFIRMED로 돌아오는 경로가 없어 환불이 성사될 수 없으므로 검사하지 않는다 — 불필요한
 * ticket-service 왕복을 없애고, 뒤이은 유스케이스가 각자의 계약대로 거절하도록 둔다.
 *
 * <p><b>REFUNDING 고착 재발행(#397)은 막지 않는다.</b> 그것은 새 환불의 개시가 아니라 <b>이미 진행 중인 환불의 복구</b>이며, 여기서 막으면
 * REFUNDING을 빠져나올 유일한 수단이 사라져 ADR 5가 제거한 흡수 상태가 되살아난다(돈은 이미 나갔을 수 있고, 좌석은 SOLD로 묶이고, 어떤 API로도 되돌릴 수
 * 없다). 대신 그 예매의 입장권이 이미 USED라면 재발행 성공 시 좌석이 반환되므로 {@code [CRITICAL]} 로그로 운영자에게 알린다.
 *
 * <p><b>트랜잭션을 열지 않는다.</b> ticket-service 왕복이 {@code @Transactional} 안에 들어가면 다운스트림 지연이 booking의 DB
 * 커넥션 풀을 물어 예매 경로 전체를 마비시킨다. 검증은 트랜잭션 밖에서 끝내고 상태 전이만 취소 유스케이스의 트랜잭션에 맡긴다({@code
 * EntryVerifyUseCase}가 booking을 동기 조회할 때와 같은 규율).
 *
 * <p>이 검증과 {@code requestRefund()} 커밋 사이에 <b>입장</b>이 끼어드는 좁은 창은 남는다(수 ms). 완전한 상호배제는 분산 락이 필요해 도입하지
 * 않았다. 역방향(REFUNDING 진입 후 입장)은 {@code EntryVerifyUseCase}가 이미 막는다.
 *
 * <p><b>HTTP 요청 경로 전용이다.</b> 여기서 던지는 {@code BusinessException}은 Kafka 리스너의 {@code
 * KafkaConsumerErrorPolicy.isPermanent()}에서 영구 실패로 분류되므로, 일시적 통신 실패가 메시지 소실로 이어진다. 이벤트 컨슈머에서 재사용하지
 * 말 것.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingValidateTicketNotUsedUseCase {

  private final BookingRepository bookingRepository;
  private final TicketRestClient ticketRestClient;
  private final RefundingStuckPolicy refundingStuckPolicy;

  /**
   * 소유자의 예매 취소 경로. 소유권을 먼저 검증해, 비소유자가 응답 코드로 타인 예매의 입장 여부를 알아내지 못하게 한다(뒤따르는 {@code
   * BookingCancelMyBookingUseCase}와 동일한 조회 조건).
   */
  public void execute(Long userId, String bookingNumber) {
    Booking booking =
        bookingRepository
            .findByBookingNumberAndUserId(bookingNumber, userId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    if (isRefundable(booking)) {
      rejectIfTicketUsed(booking);
    }
  }

  /**
   * 관리자 재환불 경로. 소유권 검증이 없는 CS 도구이므로 예매 번호로만 조회한다.
   *
   * <p>고착 복구(#397) 대상이면 차단하지 않고 좌석 반환 위험만 가시화한다. 그 외에는 사용자 경로와 같은 기준으로 막는다.
   */
  public void executeForAdmin(String bookingNumber) {
    Booking booking =
        bookingRepository
            .findByBookingNumber(bookingNumber)
            .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    if (booking.isStuckInRefunding(refundingStuckPolicy.cutoff())) {
      warnIfEntered(booking);
      return;
    }

    if (isRefundable(booking)) {
      rejectIfTicketUsed(booking);
    }
  }

  /**
   * 환불이 성사될 수 있는 예매인가. CONFIRMED는 지금 개시할 수 있고, REFUNDING은 취소 유스케이스가 읽기 전에 CONFIRMED로 복원될 수
   * 있다(#391). 나머지 상태는 CONFIRMED로 돌아오는 경로가 없다.
   */
  private boolean isRefundable(Booking booking) {
    return booking.getBookingStatus() == BookingStatus.CONFIRMED
        || booking.getBookingStatus() == BookingStatus.REFUNDING;
  }

  private void rejectIfTicketUsed(Booking booking) {
    if (ticketRestClient.isTicketUsed(booking.getId())) {
      log.warn(
          "입장을 완료한 예매라 환불을 거부합니다. bookingNumber: {}, bookingId: {}",
          booking.getBookingNumber(),
          booking.getId());
      throw new BusinessException(ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED);
    }
  }

  /**
   * 고착 복구를 통과시키되, 그 예매가 이미 입장했다면 재발행 성공 시 좌석이 반환되므로 운영자가 인지하게 한다.
   *
   * <p><b>어떤 예외도 복구를 막지 않는다.</b> 가시화에 실패했다고 고착 복구까지 막으면 흡수 상태로 되돌아간다 — 여기서는 "알 수 없으면 막는다"를 적용하지
   * 않는다. {@code BusinessException}만 잡으면 예상 밖 런타임 예외가 복구를 막으므로 넓게 삼킨다.
   */
  private void warnIfEntered(Booking booking) {
    try {
      if (ticketRestClient.isTicketUsed(booking.getId())) {
        log.error(
            "[CRITICAL] 입장을 완료한 예매의 REFUNDING 고착을 복구합니다. "
                + "환불이 성사되면 착석한 좌석이 반환되어 재판매될 수 있습니다. 확인이 필요합니다. "
                + "bookingNumber: {}, bookingId: {}, seatId: {}",
            booking.getBookingNumber(),
            booking.getId(),
            booking.getSeatId());
      }
    } catch (Exception e) {
      log.warn(
          "고착 예매의 입장권 조회에 실패해 좌석 반환 위험을 확인하지 못했습니다(복구는 계속 진행합니다). bookingId: {}",
          booking.getId(),
          e);
    }
  }
}
