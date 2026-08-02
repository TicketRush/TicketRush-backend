package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.support.SeatEventSource;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 사용자가 PENDING 예매를 즉시 취소했을 때 선점 좌석을 만료 전에 되돌린다 (#559).
 *
 * <p><b>{@code SeatReleaseSingleUseCase}와 나누는 이유.</b> 그쪽은 해제 뒤 {@code SeatHoldExpiredPublisher}로
 * {@code SeatHoldExpiredEvent}를 발행하고, booking이 그걸 받아 예매를 EXPIRED로 전이시킨다. 즉시 취소는 booking이 이미 스스로
 * CANCELED를 확정한 뒤에 오므로 그 이벤트가 필요 없을 뿐 아니라 <b>해롭다</b> — {@code expirePendingBookingById}의 {@code
 * WHERE bookingStatus = PENDING} 가드가 보통 no-op으로 막아주지만, booking의 CANCELED 커밋보다 이 발행이 앞서면 예매가
 * CANCELED가 아니라 EXPIRED로 굳는다. 발행을 아예 하지 않아 순서에 기대지 않는다.
 *
 * <p><b>{@code bookingNumber}를 함께 받는 이유.</b> 조건부 UPDATE의 가드({@code holdExpiredAt} 동등 비교)는 "내가 본 그
 * 선점 그대로인가"만 묻지 소유자를 묻지 않는다({@code SeatRepository} 주석 참고). 취소한 예매가 쥔 좌석이 맞는지는 호출 전에 여기서 확인한다.
 *
 * <p><b>멱등하다.</b> 이미 해제됐거나 다른 예매가 쥔 좌석이면 로그만 남기고 조용히 끝낸다. 호출자(booking)는 취소를 이미 확정했으므로 여기서 예외를 던져
 * 되돌릴 것이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReleaseHoldUseCase {

  private final SeatRepository seatRepository;
  private final SeatStatusEventPublisher seatStatusEventPublisher;
  private final SeatUnlockUseCase seatUnlockUseCase;

  @Transactional
  public void execute(String bookingNumber, Long seatId) {
    Seat seat = seatRepository.findById(seatId).orElse(null);

    if (seat == null) {
      log.warn("예매 즉시 취소: 대상 좌석을 DB에서 찾을 수 없습니다. (정합성 확인 필요) seatId: {}", seatId);
      return;
    }

    if (seat.getSeatStatus() != SeatStatus.HOLD) {
      log.info(
          "예매 즉시 취소: 좌석 {}이(가) HOLD가 아니어서 반납을 스킵합니다. status: {}", seatId, seat.getSeatStatus());
      return;
    }

    if (!Objects.equals(bookingNumber, seat.getBookingNumber())) {
      log.warn(
          "예매 즉시 취소: 좌석 {}을(를) 쥔 예매가 달라 반납하지 않습니다. 요청: {}, 현재: {}",
          seatId,
          bookingNumber,
          seat.getBookingNumber());
      return;
    }

    // 조회 스냅샷의 만료 시각을 가드로 넘겨 "내가 본 그 선점 그대로일 때만" 해제한다. 만료 여부는 묻지 않는다 —
    // 즉시 취소는 정의상 만료 전 HOLD를 되돌리는 것이므로 now() 비교를 넣으면 아무것도 해제하지 못한다.
    int updated =
        seatRepository.releaseExpiredHoldById(
            seatId, seat.getHoldExpiredAt(), SeatStatus.HOLD, SeatStatus.AVAILABLE);

    if (updated == 0) {
      log.info("예매 즉시 취소: 좌석 {}이(가) 조회 이후 선점 상태가 바뀌어 반납을 건너뜁니다.", seatId);
      return;
    }

    // seat는 이 시점에 detach다(clearAutomatically). releaseHold()는 DB에 나가지 않는 in-memory 조정이고,
    // 위 조건부 UPDATE가 이미 DB를 바꿨다. publishAfterCommit이 이 스냅샷을 즉시 DTO로 굳히므로 순서가 중요하다.
    seat.releaseHold();
    seatStatusEventPublisher.publishAfterCommit(seat, SeatEventSource.CANCEL_RELEASE);
    forceReleaseAfterCommit(seatId);

    log.info("예매 즉시 취소로 좌석 {}을(를) AVAILABLE로 반납했습니다. bookingNumber: {}", seatId, bookingNumber);
  }

  /**
   * Redis 락 해제를 커밋 이후로 미룬다({@code SeatConfirmSoldUseCase}와 같은 규율).
   *
   * <p>트랜잭션 안에서 부르면 동기 Redis 호출이 되어, Redis 장애가 DB 트랜잭션을 되돌린다. 좌석 DB는 이미 AVAILABLE인데 취소만 실패하는 상황을
   * 만들지 않는다.
   *
   * <p><b>락 삭제는 TTL 만료 이벤트를 없앤다.</b> {@code forceUnlock}은 키를 지우므로 Redis {@code expired} 이벤트가 다시는 오지
   * 않는다. 그래서 DB 해제가 성공한 뒤에만 부른다 — 순서가 뒤집혀 DB 해제가 실패하면 좌석을 되돌릴 신호가 사라진다.
   */
  private void forceReleaseAfterCommit(Long seatId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      seatUnlockUseCase.forceRelease(seatId);
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            seatUnlockUseCase.forceRelease(seatId);
          }
        });
  }
}
