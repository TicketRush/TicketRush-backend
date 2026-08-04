package com.ticketrush.boundedcontext.payment.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * booking-service internal 조회 API 응답의 result 본문(snake_case JSON)을 매핑한다.
 *
 * <p>{@code bookingStatus}는 booking-service {@code BookingStatus}의 이름이 문자열로 건너온 값이다. 어떤 상태에서 결제를
 * 허용할지는 정책이므로 여기서 판정하지 않는다({@code PaymentConfirmUseCase}가 판정한다).
 *
 * <p>{@code userId}는 이번 범위에서 쓰지 않지만 매핑해 둔다. booking internal API는 소유자 검증 없이 bookingId만으로 조회하므로 대조는
 * 호출자 몫인데, 그 가드는 만료 창과 원인·실패 모드가 다른 별개 결함이라 후속 이슈로 분리했다(#490). 필드를 미리 받아 두면 그때 클라이언트 변경 없이 가드만 추가하면
 * 된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingInfoResponse(
    @JsonProperty("booking_id") Long bookingId,
    @JsonProperty("user_id") Long userId,
    @JsonProperty("booking_status") String bookingStatus) {}
