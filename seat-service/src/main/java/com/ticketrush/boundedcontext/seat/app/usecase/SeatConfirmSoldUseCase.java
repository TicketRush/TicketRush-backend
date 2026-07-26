package com.ticketrush.boundedcontext.seat.app.usecase;

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

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatConfirmSoldUseCase {

  private final SeatRepository seatRepository;
  private final SeatUnlockUseCase seatUnlockUseCase;
  private final SeatStatusEventPublisher seatStatusEventPublisher;

  @Transactional
  public void execute(String bookingNumber, Long seatId) {
    int updatedCount =
        seatRepository.confirmSoldById(seatId, bookingNumber, SeatStatus.HOLD, SeatStatus.SOLD);

    // UPDATE 뒤에 읽는다. @Modifying(clearAutomatically=true)로 영속성 컨텍스트가 비워져 DB 최신 상태가 온다.
    // 아래 판정은 이 UPDATE가 트랜잭션의 첫 SQL이라는 데도 의존한다 — MySQL REPEATABLE READ의 read view는
    // 첫 consistent read 시점에 열리고, 잠금 쓰기인 UPDATE는 그것을 만들지 않는다. 그래서 이 조회가 경합
    // 트랜잭션의 커밋까지 반영된 스냅샷을 잡는다. 이 메서드(또는 상위)에 UPDATE보다 앞서는 조회가 추가되면
    // 스냅샷이 과거로 고정되어 정상 중복이 409로 뒤집힌다(안전한 방향이지만 오탐이 된다).
    Seat seat =
        seatRepository
            .findById(seatId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.SEAT_NOT_FOUND));

    if (updatedCount == 1) {
      seatStatusEventPublisher.publishAfterCommit(seat);
      forceReleaseAfterCommit(seatId);
      log.info("좌석 판매 확정 완료. bookingNumber: {}, seatId: {}", bookingNumber, seatId);
      return;
    }

    // 이미 같은 예매로 SOLD면 결제 확정 이벤트 재수신이다. 목표 상태에 이미 도달했으므로 멱등 성공으로 돌려준다(#489).
    // 여기서 409를 내면 호출자가 "정상 중복"과 "좌석 없음"을 구분할 수 없다.
    // 부수효과(SSE·Redis 락 해제)는 다시 실행하지 않는다. 둘 다 첫 확정의 같은 afterCommit 훅에 매달려 있어
    // 한쪽만 "유실됐을 수 있으니 보험"이라고 볼 근거가 없고, 락은 tryLock의 leaseTime(5분)이 어차피 걷는다.
    // 무엇보다 forceRelease를 여기서 부르면 트랜잭션 안 동기 Redis 호출이 되어, Redis 장애 시
    // SeatRedissonExceptionHandler가 503을 내고 정상 중복 재수신이 booking에서 재시도→DLT로 빠진다.
    if (seat.isSoldTo(bookingNumber)) {
      log.info("이미 같은 예매로 확정된 좌석(멱등 성공). bookingNumber: {}, seatId: {}", bookingNumber, seatId);
      return;
    }

    // 만료 해제로 AVAILABLE·booking_number NULL이 됐거나, 다른 예매가 쥔 좌석이다. 결제는 이미 끝났을 수
    // 있으므로 정상 중복과 뭉개지 않고 전용 409로 구분해 호출자(booking)가 보상을 걸 수 있게 한다(#489).
    // CRITICAL은 과금 사실을 아는 booking 쪽에서 찍는다 — 양쪽에서 찍으면 한 사건에 알림이 두 번 운다.
    log.warn(
        "좌석 판매 확정 실패(이 예매의 좌석이 아님). seatId: {}, 요청 bookingNumber: {}, "
            + "현재 status: {}, 현재 bookingNumber: {}",
        seatId,
        bookingNumber,
        seat.getSeatStatus(),
        seat.getBookingNumber());
    throw new BusinessException(ErrorStatus.SEAT_CONFIRM_NOT_OWNED);
  }

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
