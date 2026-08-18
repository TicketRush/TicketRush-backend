package com.ticketrush.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 환불 좌석 반환({@code PaymentCanceledEvent} 수신)의 예매번호 요구 여부 (#608).
 *
 * <p>{@link #requireBookingNumber}가 켜지면 이벤트에 예매번호가 없는 반환 요청을 거부한다. 좌석 소유 교차검증(ABA 방지)이 그 값 없이는 성립하지
 * 않아, 값이 비면 <b>다른 예매가 결제 완료한 SOLD 좌석</b>을 AVAILABLE로 되돌릴 수 있기 때문이다.
 *
 * <p><b>기본값이 {@code false}인 것은 배포 창 때문이다.</b> CD가 8개 서비스를 일괄 갱신하므로 payment(예매번호를 채우는 쪽)와 seat의 교체
 * 순서를 강제할 수 없고, 토픽에 아직 소비되지 않은 구버전 이벤트가 남아 있을 수도 있다. 그 구간에 이 가드가 켜져 있으면 <b>정상 취소의 좌석 반환까지 전부 막혀</b>
 * 좌석이 SOLD로 고착되는데, 관리자 강제 해제가 SOLD를 거부하므로(SEAT_SOLD_NOT_RELEASABLE) 수동 DML 외에 푸는 수단이 없다.
 *
 * <p>따라서 켜는 순서가 절차다 — payment 배포 확인 → {@code payment-canceled-topic} 컨슈머 랙 0 확인 → {@code
 * SEAT_REQUIRE_BOOKING_NUMBER=true} 주입. 되돌릴 때도 재배포 없이 이 값만 {@code false}로 내리면 된다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.seat.refund-release")
public class SeatRefundReleaseProperties {

  private boolean requireBookingNumber = false;
}
