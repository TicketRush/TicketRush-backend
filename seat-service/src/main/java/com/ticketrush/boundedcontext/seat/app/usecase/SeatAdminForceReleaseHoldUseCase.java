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
 * <p><b>관리자가 화면에서 본 {@code bookingNumber}를 전제조건으로 받는다.</b> 조건부 UPDATE의 {@code holdExpiredAt} 동등
 * 비교만으로는 부족하다 — 그 값은 이 트랜잭션에서 방금 읽은 것이라 보호하는 창이 read→UPDATE 사이 수 밀리초뿐이고, <b>관리자가 화면에서 그 선점을 본 시점부터
 * 버튼을 누르기까지의 수십 초~수 분은 전혀 보호하지 않는다.</b>
 *
 * <p>그 창에서 원래 선점이 만료되고 다른 사용자가 좌석을 재선점하면, 관리자는 A를 지우려고 눌렀는데 아무 잘못 없는 B의 결제 진행 중 예매가 EXPIRED로 종결된다.
 * 관리자 화면은 기획상 수동 갱신(새로고침·관리자 작업·좌석 선택)이라 이 창이 구조적으로 길고, 오픈런에서는 풀린 인기 좌석이 곧바로 다시 잡힌다. 전제조건이 어긋나면
 * {@link ErrorStatus#SEAT_RELEASE_CONFLICT}로 거절해 관리자가 새로 조회한 뒤 <b>새 선점자를 인지하고</b> 다시 누르게 한다. #559의
 * 즉시 취소가 같은 가드를 갖는 것과 같은 이유다.
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
  public void execute(Long adminId, Long performanceId, Long seatId, String expectedBookingNumber) {
    Seat seat =
        seatRepository
            .findByIdAndPerformanceId(seatId, performanceId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.SEAT_NOT_FOUND));

    // 실패 사유를 코드로 가른다. 뭉개면 관리자가 "왜 안 됐는지"를 알 수 없고, 화면이 안내할 다음 행동도 갈린다 —
    // 판매 완료는 환불로 가야 하고, 경합은 재조회 후 재시도가 유효하며, 이미 해제된 좌석은 할 일이 없다.
    if (seat.getSeatStatus() == SeatStatus.SOLD) {
      throw new BusinessException(ErrorStatus.SEAT_SOLD_NOT_RELEASABLE);
    }

    if (seat.getSeatStatus() != SeatStatus.HOLD) {
      throw new BusinessException(ErrorStatus.SEAT_NOT_HELD);
    }

    ensureStillTheSameHold(seat, expectedBookingNumber, seatId);

    // 조회 스냅샷의 만료 시각을 가드로 넘겨 "내가 본 그 선점 그대로일 때만" 해제한다. 만료 여부는 묻지 않는다 —
    // 강제 해제는 정의상 만료 전 HOLD를 되돌리는 것이므로 now() 비교를 넣으면 아무것도 해제하지 못한다.
    // 반드시 DB에서 읽어온 엔티티의 값을 넘긴다(now()는 나노초라 datetime(6) 절삭으로 동등 비교가 빗나간다).
    // releaseHold()가 이 값을 null로 지우므로 감사 로그용으로 미리 붙잡아 둔다.
    final String bookingNumber = seat.getBookingNumber();
    int updated =
        seatRepository.releaseExpiredHoldById(
            seatId, seat.getHoldExpiredAt(), SeatStatus.HOLD, SeatStatus.AVAILABLE);

    if (updated == 0) {
      /*
       * 조회와 UPDATE 사이에 결제가 확정됐거나 다른 예매가 재선점했다. 관리자에게는 실패로 알린다.
       *
       * holdExpiredAt을 함께 남기는 이유: 이 값이 null인 HOLD 좌석은 SQL에서 `= NULL`이 항상 UNKNOWN이라
       * 어떤 해제 경로로도(만료 스케줄러·Redis 리스너·이 API) 영원히 풀리지 않는다. Seat.hold()를 거친
       * 좌석은 non-null이 보장되지만 시딩·수동 DML로 들어온 행은 그 검증 밖이다. 로그에 null이 보이면
       * 그것이 경합이 아니라 그 상황이라는 뜻이다.
       */
      log.warn(
          "관리자 강제 해제: 좌석 {}이(가) 조회 이후 선점 상태가 바뀌어 해제하지 못했습니다. "
              + "holdExpiredAt: {}, bookingNumber: {}",
          seatId,
          seat.getHoldExpiredAt(),
          bookingNumber);
      throw new BusinessException(ErrorStatus.SEAT_RELEASE_CONFLICT);
    }

    /*
     * 감사 로그를 가장 먼저 등록한다.
     *
     * afterCommit 콜백은 Spring이 개별 try/catch로 감싸지 않아, 앞 콜백이 던지면 뒤 콜백은 실행되지 않고
     * 예외가 커밋 경로 밖으로 전파된다. 기록을 마지막에 등록하면 Redis 장애 한 번에 "해제는 됐는데 감사
     * 기록만 없는" 상태가 만들어진다 — AdminAuditLogger가 발생하지 않는다고 적어 둔 바로 그 방향이다.
     */
    AdminAuditLogger.logAfterCommit("관리자 좌석 강제 해제", adminId, performanceId, seatId, bookingNumber);

    // 순서가 중요하다. publish는 seat의 bookingNumber를 읽는데 releaseHold()가 그것을 null로 지운다.
    seatHoldExpiredPublisher.publish(seat);

    // seat는 이 시점에 detach다(clearAutomatically). releaseHold()는 DB에 나가지 않는 in-memory 조정이고,
    // 위 조건부 UPDATE가 이미 DB를 바꿨다. publishAfterCommit이 이 스냅샷을 즉시 DTO로 굳히므로 순서가 중요하다.
    seat.releaseHold();
    seatStatusEventPublisher.publishAfterCommit(seat, SeatEventSource.ADMIN_FORCE_RELEASE);
    forceReleaseAfterCommit(seatId);
  }

  /**
   * 관리자가 화면에서 본 선점이 지금도 그대로인지 확인한다.
   *
   * <p><b>{@code bookingNumber}가 없는 HOLD 좌석은 이 검사를 건너뛴다.</b> 가드의 목적은 "관리자가 모르는 제3의 예매를 실수로 종결시키지 않는
   * 것"인데, 좌석이 예매를 가리키지 않으면 종결될 예매 자체가 없다({@code SeatHoldExpiredPublisher}도 이 경우 발행을 스킵한다). #95 이전에
   * 만들어진 그런 좌석은 오히려 관리자가 풀어 줘야 할 대상이라, 여기서 막으면 유일한 탈출구가 사라진다.
   */
  private void ensureStillTheSameHold(Seat seat, String expectedBookingNumber, Long seatId) {
    String actual = seat.getBookingNumber();
    if (actual == null || actual.equals(expectedBookingNumber)) {
      return;
    }

    log.warn(
        "관리자 강제 해제: 좌석 {}을(를) 쥔 예매가 관리자가 조회한 것과 달라 해제하지 않습니다. 요청: {}, 현재: {}",
        seatId,
        expectedBookingNumber,
        actual);
    throw new BusinessException(ErrorStatus.SEAT_RELEASE_CONFLICT);
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
      releaseLockQuietly(seatId);
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            releaseLockQuietly(seatId);
          }
        });
  }

  /**
   * Redis 락 삭제 실패를 삼킨다.
   *
   * <p>이 시점에 DB 커밋은 이미 끝났고 좌석은 AVAILABLE이다. 여기서 던지면 뒤에 등록된 afterCommit 콜백이 끊기고 관리자에게 500이 나가는데, 정작
   * 해제는 성사된 뒤라 "실패했다"는 잘못된 신호가 된다.
   *
   * <p>삼켜도 안전한 이유는 락이 자가 치유되기 때문이다 — 잔여 TTL(최대 {@code Seat.HOLD_TTL_MINUTES}분)이 지나면 키가 사라진다. 그 사이
   * 좌석은 DB상 예매 가능인데 선점 시도가 락에서 막히므로, 원인을 찾을 수 있도록 CRITICAL로 남긴다.
   */
  private void releaseLockQuietly(Long seatId) {
    try {
      seatUnlockUseCase.forceRelease(seatId);
    } catch (RuntimeException e) {
      log.error(
          "[CRITICAL] 관리자 강제 해제 후 Redis 락 삭제 실패. 좌석은 해제됐으나 잔여 TTL({}분) 동안 재선점이 막힙니다. seatId: {}",
          Seat.HOLD_TTL_MINUTES,
          seatId,
          e);
    }
  }
}
