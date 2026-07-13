package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
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
 * 것이다. 확정된 정책은 <b>입장한 예매는 환불하지 않는다</b>이므로(ADR 5), 좌석이 반환될 상황을 환불 요청 시점에 없앤다. 예매는 CONFIRMED에 머물러 상태
 * 전이가 없으므로 흡수 상태가 생기지 않는다.
 *
 * <p><b>차단 대상은 CONFIRMED 예매의 환불 개시뿐이다.</b> 환불은 CONFIRMED에서만 시작되므로 그 밖의 상태는 이 검증의 대상이 아니다. 특히
 * REFUNDING 고착 재발행(#397)을 막아선 안 된다 — 그것은 새 환불의 개시가 아니라 <b>이미 진행 중인 환불의 복구</b>이며, 여기서 막으면 REFUNDING을
 * 빠져나올 유일한 수단이 사라져 ADR 5가 제거한 흡수 상태가 되살아난다(돈은 이미 나갔을 수 있고, 좌석은 SOLD로 묶이고, 어떤 API로도 되돌릴 수 없다). 대신 그
 * 예매의 입장권이 이미 USED라면 재발행 성공 시 좌석이 반환되므로 {@code [CRITICAL]} 로그로 운영자에게 알린다. CANCELED·EXPIRED 등 나머지
 * 상태는 뒤이은 취소·재환불 유스케이스가 각자의 계약대로 거절한다.
 *
 * <p><b>트랜잭션을 열지 않는다.</b> ticket-service 왕복이 {@code @Transactional} 안에 들어가면 다운스트림 지연이 booking의 DB
 * 커넥션 풀을 물어 예매 경로 전체를 마비시킨다. 검증은 트랜잭션 밖에서 끝내고 상태 전이만 취소 유스케이스의 트랜잭션에 맡긴다({@code
 * EntryVerifyUseCase}가 booking을 동기 조회할 때와 같은 규율).
 *
 * <p>이 검증과 {@code requestRefund()} 커밋 사이에 입장이 끼어드는 좁은 창은 남는다(수 ms). 완전한 상호배제는 분산 락이 필요해 도입하지 않았다.
 * 역방향(REFUNDING 진입 후 입장)은 {@code EntryVerifyUseCase}가 이미 막는다.
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

  /**
   * 소유자의 예매 취소 경로. 소유권을 먼저 검증해, 비소유자가 응답 코드로 타인 예매의 입장 여부를 알아내지 못하게 한다(뒤따르는 {@code
   * BookingCancelMyBookingUseCase}와 동일한 조회 조건).
   */
  public void execute(Long userId, String bookingNumber) {
    Booking booking =
        bookingRepository
            .findByBookingNumberAndUserId(bookingNumber, userId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    validate(booking);
  }

  /** 관리자 재환불 경로. 소유권 검증이 없는 CS 도구이므로 예매 번호로만 조회한다. */
  public void executeForAdmin(String bookingNumber) {
    Booking booking =
        bookingRepository
            .findByBookingNumber(bookingNumber)
            .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    validate(booking);
  }

  private void validate(Booking booking) {
    if (booking.getBookingStatus() != BookingStatus.CONFIRMED) {
      warnIfEnteredRefunding(booking);
      return;
    }

    if (ticketRestClient.isTicketUsed(booking.getId())) {
      log.warn(
          "입장을 완료한 예매라 환불을 거부합니다. bookingNumber: {}, bookingId: {}",
          booking.getBookingNumber(),
          booking.getId());
      throw new BusinessException(ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED_TICKET_USED);
    }
  }

  /**
   * REFUNDING 고착 복구(#397)는 통과시키되, 그 예매가 이미 입장했다면 재발행 성공 시 좌석이 반환되므로 운영자가 인지하게 한다.
   *
   * <p>조회 실패는 삼킨다. 가시화를 못 했다고 고착 복구까지 막으면 흡수 상태로 되돌아가기 때문이다 — 여기서는 "알 수 없으면 막는다"를 적용하지 않는다.
   */
  private void warnIfEnteredRefunding(Booking booking) {
    if (booking.getBookingStatus() != BookingStatus.REFUNDING) {
      return;
    }

    try {
      if (ticketRestClient.isTicketUsed(booking.getId())) {
        log.error(
            "[CRITICAL] 입장을 완료한 예매의 환불이 REFUNDING으로 진행 중입니다. "
                + "환불이 성사되면 착석한 좌석이 반환되어 재판매될 수 있습니다. 확인이 필요합니다. "
                + "bookingNumber: {}, bookingId: {}, seatId: {}",
            booking.getBookingNumber(),
            booking.getId(),
            booking.getSeatId());
      }
    } catch (BusinessException e) {
      log.warn(
          "REFUNDING 예매의 입장권 조회에 실패해 좌석 반환 위험을 확인하지 못했습니다(고착 복구는 계속 진행합니다). bookingId: {}",
          booking.getId(),
          e);
    }
  }
}
