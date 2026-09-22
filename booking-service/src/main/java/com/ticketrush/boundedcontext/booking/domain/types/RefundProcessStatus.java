package com.ticketrush.boundedcontext.booking.domain.types;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 관리자 환불 화면이 쓰는 환불 처리 상태 (#675). <b>저장되지 않는다</b> — {@link BookingStatus}와 {@code refundFailedAt}에서
 * 매 조회 파생한다.
 *
 * <p>새 예매 상태를 만들지 않는 것이 핵심이다. 환불 실패를 상태로 두면 빠져나올 전이가 없는 흡수 상태가 되므로 ADR 0005가 {@code REFUND_FAILED}를
 * 제거했고, 그 결정을 되돌리지 않은 채 화면이 필요로 하는 구분만 읽기 시점에 만든다.
 *
 * <p>판정 순서가 규칙의 전부다 — <b>{@code bookingStatus}를 먼저 보고, CONFIRMED일 때만 {@code refundFailedAt}을
 * 본다.</b> {@code refundFailedAt}은 재환불 시 지워지지 않으므로(ADR 0005) 실패 이력이 있는 예매가 재시도로 REFUNDING·REFUNDED에
 * 가 있을 수 있는데, 실패 시각을 먼저 보면 그 건이 진행 중·완료인데도 실패로 분류된다.
 */
@Getter
@AllArgsConstructor
public enum RefundProcessStatus {
  IN_PROGRESS("환불 진행 중"), // booking_status = REFUNDING
  COMPLETED("환불 완료"), // booking_status = REFUNDED
  FAILED("환불 실패(미해결)"); // booking_status = CONFIRMED AND refund_failed_at IS NOT NULL

  private final String description;

  /**
   * 예매 상태와 실패 시각에서 환불 처리 상태를 파생한다.
   *
   * <p><b>환불 대상이 아닌 예매를 넘기면 {@link IllegalStateException}이다.</b> null을 돌려주면 화면이 빈칸으로 거짓말을 하고, 그때
   * 의심해야 할 것은 데이터가 아니라 조회 조건과 이 파생 규칙이 갈라졌다는 사실이다. 호출자는 환불 대상만 뽑는 조회를 거치므로 정상 경로에서는 도달하지 않는다.
   *
   * <p><b>{@code BusinessException}이 아닌 것은 의도다.</b> 그쪽은 사용자에게 설명할 수 있는 실패({@code ErrorStatus} 코드가
   * 붙는 것)를 위한 통로인데, 여기 도달했다는 것은 사용자가 잘못한 것이 없고 코드의 불변식이 깨졌다는 뜻이다. 4xx로 포장하면 버그가 정상 응답처럼 흘러간다.
   *
   * <p>대신 <b>그 갈라짐을 런타임이 아니라 테스트에서 만나도록</b> {@code BookingRepositoryTest}가 조회 결과 전 행을 이 메서드에 통과시킨다
   * — 조회 조건에 분기를 더하고 여기를 고치지 않으면 그 테스트가 먼저 빨개진다.
   */
  public static RefundProcessStatus from(
      BookingStatus bookingStatus, LocalDateTime refundFailedAt) {
    if (bookingStatus == BookingStatus.REFUNDING) {
      return IN_PROGRESS;
    }
    if (bookingStatus == BookingStatus.REFUNDED) {
      return COMPLETED;
    }
    if (bookingStatus == BookingStatus.CONFIRMED && refundFailedAt != null) {
      return FAILED;
    }

    throw new IllegalStateException("환불 대상이 아닌 예매다. bookingStatus: " + bookingStatus);
  }
}
