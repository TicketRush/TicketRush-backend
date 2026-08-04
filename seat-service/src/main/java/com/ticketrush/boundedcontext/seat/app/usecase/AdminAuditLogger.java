package com.ticketrush.boundedcontext.seat.app.usecase;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 좌석 상태를 바꾸는 관리자 행위의 감사 로그 (#562). booking-service의 동명 클래스와 같은 규율이며, 그쪽이 package-private이라 여기에 복제한다
 * — common으로 올리면 두 도메인이 서로 다른 식별자(예매 번호 / 좌석 ID)를 하나의 시그니처에 우겨넣게 된다.
 *
 * <p><b>커밋 이후에 기록한다.</b> 트랜잭션 안에서 찍으면 조건부 UPDATE가 경합에 밀려 롤백됐을 때 일어나지 않은 해제가 감사 기록에 남고, 사고 조사에서 실제
 * 이력과 구분되지 않는다. 역방향(해제는 됐는데 기록이 없음)은 발생하지 않으므로 은폐 위험은 없다.
 *
 * <p>트랜잭션 밖에서 호출되면 동기화를 걸 수 없으므로 즉시 기록한다 — 그 경우 롤백될 커밋 자체가 없다.
 */
@Slf4j
final class AdminAuditLogger {

  private AdminAuditLogger() {}

  static void logAfterCommit(
      String action, Long adminId, Long performanceId, Long seatId, String bookingNumber) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      write(action, adminId, performanceId, seatId, bookingNumber);
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            write(action, adminId, performanceId, seatId, bookingNumber);
          }
        });
  }

  private static void write(
      String action, Long adminId, Long performanceId, Long seatId, String bookingNumber) {
    log.info(
        "[ADMIN-AUDIT] {}. adminId: {}, performanceId: {}, seatId: {}, bookingNumber: {}",
        action,
        adminId,
        performanceId,
        seatId,
        bookingNumber);
  }
}
