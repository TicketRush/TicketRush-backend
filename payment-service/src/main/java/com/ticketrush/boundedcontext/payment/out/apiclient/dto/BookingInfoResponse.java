package com.ticketrush.boundedcontext.payment.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * booking-service internal 조회 API 응답의 result 본문(snake_case JSON)을 매핑한다.
 *
 * <p>{@code bookingStatus}는 booking-service {@code BookingStatus}의 이름이 문자열로 건너온 값이다. 어떤 상태에서 결제를
 * 허용할지는 정책이므로 여기서 판정하지 않는다({@code PaymentConfirmUseCase}가 판정한다).
 *
 * <p>{@code userId}는 예매 소유자다. booking internal API는 소유자 검증 없이 bookingId만으로 조회하므로 대조는 호출자 몫이며,
 * {@code PaymentConfirmUseCase}가 결제 확정 요청자와 이 값을 대조해 남의 예매로의 결제 귀속을 막는다(#572, ADR 0011). #490에서 필드만
 * 먼저 매핑해 둔 덕에 클라이언트 변경 없이 가드만 추가됐다.
 *
 * <p>세 필드 모두 박스 타입이라 null이 될 수 있다. 특히 {@code @JsonIgnoreProperties(ignoreUnknown = true)}라 booking이
 * 필드명을 바꾸면 예외 대신 조용히 null이 되므로, 판정하는 쪽이 null을 "통과"가 아니라 "판정 불가"로 다뤄야 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingInfoResponse(
    @JsonProperty("booking_id") Long bookingId,
    @JsonProperty("user_id") Long userId,
    @JsonProperty("booking_status") String bookingStatus) {}
