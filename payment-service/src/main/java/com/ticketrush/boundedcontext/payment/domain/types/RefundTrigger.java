package com.ticketrush.boundedcontext.payment.domain.types;

import java.util.Locale;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 환불을 유발한 주체 (#492).
 *
 * <p>{@code reason}은 PG 취소 요청과 {@code Refund.reason}에 함께 실려 PG 관리자 화면·정산 분석에서 사고 보상 건을 사용자 취소와 구분하게
 * 한다. 문자열을 그대로 파라미터로 받지 않는 이유는 {@code bookingNumber}와 타입이 같아 인자 순서를 바꿔도 컴파일이 통과하고, 그 실수가 PG와 DB에
 * 예매번호를 사유로 기록한 뒤에야 드러나기 때문이다.
 *
 * <p>{@code tag()}는 메트릭 태그 값이다. 열거형이라 태그 카디널리티의 유한성이 컴파일 시점에 보장된다(seat-service {@code
 * SeatEventSource}와 같은 방식).
 */
@Getter
@AllArgsConstructor
public enum RefundTrigger {
  /** 사용자·관리자가 예매를 취소해 요청한 환불 (#91). */
  USER_CANCEL("사용자 예매 취소"),
  /** 과금 후 좌석 확정이 실패해 시스템이 되돌리는 보상 환불 (#492). */
  SEAT_CONFIRM_FAILED("좌석 확정 실패 자동 환불"),
  /**
   * 결제 확정 신호가 유실돼 예매가 만료된 뒤, 남은 과금을 시스템이 되돌리는 보상 환불 (#607).
   *
   * <p>{@link #SEAT_CONFIRM_FAILED}와 나눠 둔 것은 두 사고의 원인이 다르기 때문이다. 그쪽은 좌석 확정이 실패했다는 <b>신호가 도착한</b>
   * 건이고, 이쪽은 <b>신호가 오지 않아</b> 대조로 찾아낸 건이다. 한 값에 접으면 발행 유실률(#574의 {@code
   * ticketrush.event.publish.failed})과 겹쳐 읽어야 할 사고 축이 좌석 실패에 섞여 사라진다.
   */
  CONFIRM_SIGNAL_LOST("결제 확정 신호 유실 자동 환불");

  private final String reason;

  /* 로케일에 따라 값이 달라지면 안 되는 태그다(tr_TR 은 I 를 dotless ı 로 내린다). */
  public String tag() {
    return name().toLowerCase(Locale.ROOT);
  }
}
