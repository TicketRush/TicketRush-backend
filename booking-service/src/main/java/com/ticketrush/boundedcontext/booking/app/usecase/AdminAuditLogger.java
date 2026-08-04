package com.ticketrush.boundedcontext.booking.app.usecase;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * PG 환불을 유발하는 관리자 행위의 감사 로그 (ADR 0005).
 *
 * <p><b>커밋 이후에 기록한다.</b> 트랜잭션 안에서 찍으면 {@code Booking}의 낙관적 락 충돌(사용자 취소와 경합)로 롤백됐을 때 일어나지 않은 환불이 감사
 * 기록에 남고, 사고 조사에서 실제 이력과 구분되지 않는다. 역방향(환불은 됐는데 기록이 없음)은 발생하지 않으므로 은폐 위험은 없다.
 *
 * <p>트랜잭션 밖에서 호출되면 동기화를 걸 수 없으므로 즉시 기록한다 — 그 경우 롤백될 커밋 자체가 없다.
 */
@Slf4j
final class AdminAuditLogger {

  private AdminAuditLogger() {}

  static void logAfterCommit(
      String action, Long adminId, String bookingNumber, Long bookingId, Long userId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      write(action, adminId, bookingNumber, bookingId, userId);
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            write(action, adminId, bookingNumber, bookingId, userId);
          }
        });
  }

  private static void write(
      String action, Long adminId, String bookingNumber, Long bookingId, Long userId) {
    log.info(
        "[ADMIN-AUDIT] {}. adminId: {}, bookingNumber: {}, bookingId: {}, userId: {}",
        action,
        adminId,
        bookingNumber,
        bookingId,
        userId);
  }
}
