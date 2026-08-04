package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.support.SeatEventSource;
import com.ticketrush.boundedcontext.seat.app.support.SeatHoldExpiredPublisher;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.types.SeatStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 관리자가 HOLD 좌석의 선점을 만료 전에 강제로 해제한다 (#562).
 *
 * <p><b>예매 정합은 기존 만료 계약을 그대로 탄다.</b> 해제 후 {@link SeatHoldExpiredPublisher}로 {@code
 * SeatHoldExpiredEvent}를 발행하면 booking이 그것을 받아 PENDING 예매를 EXPIRED로 전이하고, payment의 만료 예매 가드까지 재발행된다.
 * 즉 이 유스케이스는 <b>좌석발 해제</b>라서 만료와 같은 의미론을 갖는다 — 예매발 취소(#559 {@link SeatReleaseHoldUseCase})가
 * CANCELED를 확정한 뒤 오는 것과 정반대다. 관리자 전용 종료 상태를 새로 만들면 같은 좌석 반납에 두 가지 예매 상태가 남는다.
 *
 * <p><b>{@link SeatReleaseSingleUseCase}와 나누는 이유.</b> 그쪽은 Redis 키 만료 이벤트가 트리거라 락이 이미 사라진 뒤에 돌고, 실패를
 * 전부 조용한 no-op으로 삼킨다. 강제 해제는 <b>만료 전</b>이라 락 키가 살아 있어 명시적으로 지워야 하고(안 그러면 DB는 AVAILABLE인데 잔여 TTL 동안
 * 재선점이 막힌다), 관리자에게는 조용한 성공이 아니라 409가 필요하다.
 *
 * <p><b>소유권({@code bookingNumber})을 묻지 않는다.</b> 관리자는 예매가 아니라 좌석을 기준으로 행위한다. 대신 조건부 UPDATE의 {@code
 * holdExpiredAt} 동등 비교가 "내가 본 그 선점 그대로일 때만" 해제하도록 ABA를 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatAdminForceReleaseHoldUseCase {

  private final SeatRepository seatRepository;
  private final SeatStatusEventPublisher seatStatusEventPublisher;
  private final SeatHoldExpiredPublisher seatHoldExpiredPublisher;
  private final SeatUnlockUseCase seatUnlockUseCase;

  @Transactional
  public void execute(Long adminId, Long performanceId, Long seatId) {
    Seat seat =
        seatRepository
            .findByIdAndPerformanceId(seatId, performanceId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.SEAT_NOT_FOUND));

    if (seat.getSeatStatus() != SeatStatus.HOLD) {
      throw new BusinessException(ErrorStatus.SEAT_NOT_HELD);
    }

    // 조회 스냅샷의 만료 시각을 가드로 넘겨 "내가 본 그 선점 그대로일 때만" 해제한다. 만료 여부는 묻지 않는다 —
    // 강제 해제는 정의상 만료 전 HOLD를 되돌리는 것이므로 now() 비교를 넣으면 아무것도 해제하지 못한다.
    // 반드시 DB에서 읽어온 엔티티의 값을 넘긴다(now()는 나노초라 datetime(6) 절삭으로 동등 비교가 빗나간다).
    // releaseHold()가 이 값을 null로 지우므로 감사 로그용으로 미리 붙잡아 둔다.
    final String bookingNumber = seat.getBookingNumber();
    int updated =
        seatRepository.releaseExpiredHoldById(
            seatId, seat.getHoldExpiredAt(), SeatStatus.HOLD, SeatStatus.AVAILABLE);

    if (updated == 0) {
      // 조회와 UPDATE 사이에 결제가 확정됐거나 다른 예매가 재선점했다. 관리자에게는 실패로 알린다.
      log.info("관리자 강제 해제: 좌석 {}이(가) 조회 이후 선점 상태가 바뀌어 해제하지 못했습니다.", seatId);
      throw new BusinessException(ErrorStatus.SEAT_NOT_HELD);
    }

    // 순서가 중요하다. publish는 seat의 bookingNumber를 읽는데 releaseHold()가 그것을 null로 지운다.
    seatHoldExpiredPublisher.publish(seat);

    // seat는 이 시점에 detach다(clearAutomatically). releaseHold()는 DB에 나가지 않는 in-memory 조정이고,
    // 위 조건부 UPDATE가 이미 DB를 바꿨다. publishAfterCommit이 이 스냅샷을 즉시 DTO로 굳히므로 순서가 중요하다.
    seat.releaseHold();
    seatStatusEventPublisher.publishAfterCommit(seat, SeatEventSource.ADMIN_FORCE_RELEASE);
    forceReleaseAfterCommit(seatId);
    AdminAuditLogger.logAfterCommit("관리자 좌석 강제 해제", adminId, performanceId, seatId, bookingNumber);

    log.info("관리자 강제 해제로 좌석 {}을(를) AVAILABLE로 반납했습니다. bookingNumber: {}", seatId, bookingNumber);
  }

  /**
   * Redis 락 해제를 커밋 이후로 미룬다({@link SeatReleaseHoldUseCase}와 같은 규율).
   *
   * <p>트랜잭션 안에서 부르면 동기 Redis 호출이 되어, Redis 장애가 DB 트랜잭션을 되돌린다. 좌석 DB는 이미 AVAILABLE인데 해제만 실패하는 상황을
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
