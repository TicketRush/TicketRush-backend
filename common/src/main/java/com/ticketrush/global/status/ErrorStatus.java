package com.ticketrush.global.status;

import java.util.Optional;
import java.util.function.Predicate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorStatus {
  INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_500", "서버 에러, 관리자에게 문의 바랍니다."),
  BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_400", "잘못된 요청입니다."),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON_401", "인증이 필요합니다."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_403", "금지된 요청입니다."),
  NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_404", "페이지를 찾을 수 없습니다."),
  // VALID 400
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "VALID_400_001", "입력값이 올바르지 않습니다."),
  // Json 501
  JSON_PROCESSING_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "JSON_501_001", "데이터 변환 중 오류가 발생했습니다."),
  // Event 500
  EVENT_PUBLISH_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "EVENT_500_001", "이벤트 발행 중 오류가 발생했습니다."),
  // Infra 500
  INFRA_KAFKA_PUBLISH_FAILED(
      HttpStatus.INTERNAL_SERVER_ERROR, "INFRA_500_001", "카프카 메시지 발행 중 인프라 오류가 발생했습니다."),

  // Auth 400
  AUTH_PROVIDER_NOT_SUPPORT(HttpStatus.BAD_REQUEST, "AUTH_400_001", "지원하지 않는 소셜로그인입니다."),
  AUTH_KAKAO_TOKEN_FAILED(HttpStatus.BAD_REQUEST, "AUTH_400_002", "잘못된 카카오 토큰입니다."),
  AUTH_USER_BAD_REQUEST(HttpStatus.BAD_REQUEST, "AUTH_400_003", "user-service 요청이 올바르지 않습니다."),
  AUTH_OAUTH_INVALID_REDIRECT_URI(
      HttpStatus.BAD_REQUEST, "AUTH_400_004", "유효하지 않은 Redirect URI입니다."),
  AUTH_GOOGLE_TOKEN_FAILED(HttpStatus.BAD_REQUEST, "AUTH_400_005", "잘못된 구글 토큰입니다."),
  AUTH_NAVER_TOKEN_FAILED(HttpStatus.BAD_REQUEST, "AUTH_400_006", "네이버 토큰 요청에 실패했습니다."),
  AUTH_EMAIL_ALREADY_EXISTS(HttpStatus.BAD_REQUEST, "AUTH_400_007", "이미 가입된 이메일입니다."),
  AUTH_EMAIL_SEND_FAILED(HttpStatus.BAD_REQUEST, "AUTH_400_008", "Email 인증번호 발송에 실패하였습니다."),
  AUTH_EMAIL_EXISTS_CHECK_BAD_REQUEST(
      HttpStatus.BAD_REQUEST, "AUTH_400_009", "이메일 중복 확인 요청이 올바르지 않습니다."),
  AUTH_NUMBER_NOT_MATCH(HttpStatus.BAD_REQUEST, "AUTH_400_010", "인증번호가 일치하지 않습니다."),
  EMAIL_AUTH_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "AUTH_400_011", "이메일 인증이 완료되지 않았습니다."),
  AUTH_LOGIN_BAD_REQUEST(HttpStatus.BAD_REQUEST, "AUTH_400_012", "로그인 요청이 올바르지 않습니다."),

  // Auth 401
  AUTH_INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_401_001", "유효하지 않은 Refresh Token입니다."),
  AUTH_INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_401_002", "유효하지 않은 Access Token입니다."),
  AUTH_INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_401_003", "유효하지 않은 JWT 토큰입니다."),
  AUTH_EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_401_004", "만료된 JWT 토큰입니다."),
  AUTH_INVALID_TOKEN_TYPE(HttpStatus.UNAUTHORIZED, "AUTH_401_005", "Access Token만 사용할 수 있습니다."),
  AUTH_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "AUTH_401_006", "이메일 또는 비밀번호가 올바르지 않습니다."),

  // Auth 403
  AUTH_ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_403_001", "접근 권한이 없습니다."),
  AUTH_EMAIL_AUTH_NUMBER_SEND_TOO_FREQUENT(
      HttpStatus.FORBIDDEN, "AUTH_403_002", "이메일 인증 번호는 60초 후 다시 요청할 수 있습니다."),
  AUTH_NUMBER_VERIFY_ATTEMPT_EXCEEDED(
      HttpStatus.FORBIDDEN, "AUTH_403_003", "인증번호 입력 횟수를 초과했습니다. 인증번호를 다시 발급해주세요."),

  // Auth 404
  AUTH_NUMBER_VERIFY_NOT_FOUND(HttpStatus.NOT_FOUND, "AUTH_404_001", "인증번호가 만료되었거나 존재하지 않습니다."),

  // Auth 500
  AUTH_USER_SERVER_ERROR(
      HttpStatus.INTERNAL_SERVER_ERROR, "AUTH_500_001", "user-service 내부 오류가 발생했습니다."),
  AUTH_EMAIL_EXISTS_CHECK_SERVER_ERROR(
      HttpStatus.INTERNAL_SERVER_ERROR, "AUTH_500_002", "이메일 중복 확인 중 user-service 서버 오류가 발생했습니다."),
  AUTH_EMAIL_EXISTS_CHECK_COMMUNICATION_FAILED(
      HttpStatus.INTERNAL_SERVER_ERROR, "AUTH_500_003", "이메일 중복 확인 중 user-service와 통신에 실패했습니다."),

  // Auth 503
  AUTH_USER_COMMUNICATION_FAILED(
      HttpStatus.SERVICE_UNAVAILABLE, "AUTH_503_001", "user-service와 통신에 실패했습니다."),
  AUTH_KAKAO_INFO_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_503_002", "카카오 사용자 정보 조회를 실패하였습니다."),
  AUTH_GOOGLE_INFO_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_503_003", "구글 사용자 정보 조회에 실패했습니다."),
  AUTH_NAVER_INFO_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_503_004", "네이버 사용자 정보 조회에 실패했습니다."),

  // Booking 404
  BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "BOOKING_404_001", "해당 예매를 찾을 수 없습니다."),

  // Booking 409
  BOOKING_CANCEL_NOT_ALLOWED(HttpStatus.CONFLICT, "BOOKING_409_001", "취소할 수 없는 예매 상태입니다."),
  BOOKING_CONFIRM_NOT_ALLOWED(HttpStatus.CONFLICT, "BOOKING_409_002", "확정할 수 없는 예매 상태입니다."),
  BOOKING_EXPIRED(HttpStatus.CONFLICT, "BOOKING_409_003", "만료된 예매입니다."),
  BOOKING_SEAT_MISMATCH(HttpStatus.CONFLICT, "BOOKING_409_004", "예매와 좌석 정보가 일치하지 않습니다."),
  BOOKING_REFUND_RETRY_NOT_ALLOWED(
      HttpStatus.CONFLICT, "BOOKING_409_005", "환불에 실패한 예매만 재환불할 수 있습니다."),

  // Booking 500
  BOOKING_NUMBER_RETRY_EXCEEDED(
      HttpStatus.INTERNAL_SERVER_ERROR, "BOOKING_500_001", "재시도 횟수를 초과하여 고유한 예약 번호를 생성할 수 없습니다."),

  // Seat 400
  SEAT_HOLD_TIME_INVALID(HttpStatus.BAD_REQUEST, "SEAT_400_001", "선점 만료 시간은 현재 시간 이후여야 합니다."),
  SEAT_BOOKING_NUMBER_REQUIRED(HttpStatus.BAD_REQUEST, "SEAT_400_002", "좌석 선점에는 예매 번호가 필요합니다."),

  // Seat 404
  SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "SEAT_404_001", "해당 좌석을 찾을 수 없습니다."),

  // Seat 409
  SEAT_NOT_AVAILABLE(HttpStatus.CONFLICT, "SEAT_409_001", "현재 예매 가능한 좌석이 아닙니다."),
  SEAT_ALREADY_LOCKED(HttpStatus.CONFLICT, "SEAT_409_002", "이미 다른 사용자가 결제를 진행 중인 좌석입니다."),

  // Performance 400
  PERFORMANCE_MAIN_IMAGE_MISSING(HttpStatus.BAD_REQUEST, "PERFORMANCE_400_001", "메인 이미지는 필수입니다."),
  PERFORMANCE_MODEL_3D_MISSING(HttpStatus.BAD_REQUEST, "PERFORMANCE_400_002", "3D 모델 파일은 필수입니다."),
  PERFORMANCE_GALLERY_LIMIT_EXCEEDED(
      HttpStatus.BAD_REQUEST, "PERFORMANCE_400_003", "갤러리 이미지는 최대 3개까지 업로드할 수 있습니다."),
  PERFORMANCE_INVALID_STATUS_TRANSITION(
      HttpStatus.BAD_REQUEST, "PERFORMANCE_400_004", "유효하지 않은 공연 상태 전환입니다."),
  PERFORMANCE_NOT_ON_SALE(HttpStatus.BAD_REQUEST, "PERFORMANCE_400_005", "예매 가능한 공연이 아닙니다."),
  PERFORMANCE_INVALID_SORT_PROPERTY(
      HttpStatus.BAD_REQUEST, "PERFORMANCE_400_006", "허용되지 않은 정렬 필드입니다."),
  PERFORMANCE_INVALID_PRICE_RANGE(
      HttpStatus.BAD_REQUEST, "PERFORMANCE_400_007", "최소 가격은 최대 가격보다 클 수 없습니다."),

  // Performance 409
  PERFORMANCE_HAS_SOLD_SEATS(
      HttpStatus.CONFLICT, "PERFORMANCE_409_001", "예매 완료된 좌석이 있어 삭제할 수 없습니다."),

  // Performance 404
  PERFORMANCE_NOT_FOUND(HttpStatus.NOT_FOUND, "PERFORMANCE_404_001", "공연이 존재하지 않습니다."),

  // File 400
  FILE_EMPTY(HttpStatus.BAD_REQUEST, "FILE_400_001", "업로드할 파일이 비어있습니다."),
  FILE_INVALID_EXTENSION(HttpStatus.BAD_REQUEST, "FILE_400_002", "파일 확장자가 올바르지 않습니다."),
  FILE_EXTENSION_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "FILE_400_003", "허용되지 않은 파일 형식입니다."),

  // User 400
  USER_SOCIAL_PROVIDER_REQUIRED(HttpStatus.BAD_REQUEST, "USER_400_001", "socialProvider는 필수입니다."),
  USER_SOCIAL_ID_REQUIRED(HttpStatus.BAD_REQUEST, "USER_400_002", "socialId는 필수입니다."),
  USER_SOCIAL_PROVIDER_INVALID(
      HttpStatus.BAD_REQUEST, "USER_400_003", "socialProviderId가 유효하지 않습니다."),
  USER_EMAIL_REQUIRED(HttpStatus.BAD_REQUEST, "USER_400_004", "이메일은 필수입니다."),
  USER_PASSWORD_REQUIRED(HttpStatus.BAD_REQUEST, "USER_400_005", "비밀번호를 입력해주세요."),
  USER_PASSWORD_NOT_MATCH(HttpStatus.BAD_REQUEST, "USER_400_006", "비밀번호와 비밀번호 확인이 일치하지 않습니다."),
  USER_PASSWORD_INVALID(
      HttpStatus.BAD_REQUEST, "USER_400_007", "비밀번호는 소문자, 숫자, 특수문자를 포함하여 12자 이상이어야 합니다."),
  USER_EMAIL_ALREADY_EXISTS(HttpStatus.BAD_REQUEST, "USER_400_008", "이미 가입된 이메일입니다."),
  USER_NAME_REQUIRED(HttpStatus.BAD_REQUEST, "USER_400_009", "이름을 입력해주세요."),

  // User 401
  EMAIL_VERIFICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "USER_401_001", "이메일 인증번호 일치 확인 인증이 필요합니다."),

  // User 404
  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_404_001", "해당 사용자를 찾을 수 없습니다."),

  // Payment 400
  PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "PAYMENT_400_001", "결제 금액이 일치하지 않습니다."),
  PAYMENT_PROVIDER_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "PAYMENT_400_002", "지원하지 않는 결제 수단입니다."),
  PAYMENT_METHOD_REJECTED(
      HttpStatus.BAD_REQUEST, "PAYMENT_400_003", "결제가 거절되었습니다. 다른 결제수단으로 시도해주세요."),
  PAYMENT_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "PAYMENT_400_004", "카드 한도 또는 결제 한도를 초과했습니다."),
  /* 환불 가능 기한 검증은 공연 시작 시간(performance 도메인) 연동 후속 작업에서 사용 예정. (#22 보류) */
  PAYMENT_REFUND_DEADLINE_EXCEEDED(
      HttpStatus.BAD_REQUEST, "PAYMENT_400_005", "환불 가능 기한이 지나 환불할 수 없습니다."),
  PAYMENT_WEBHOOK_PAYLOAD_INVALID(
      HttpStatus.BAD_REQUEST, "PAYMENT_400_006", "Webhook 페이로드를 해석할 수 없습니다."),

  // Payment 401
  PAYMENT_WEBHOOK_INVALID(HttpStatus.UNAUTHORIZED, "PAYMENT_401_001", "Webhook 검증에 실패했습니다."),

  // Payment 404
  PAYMENT_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_404_001", "결제 세션이 만료되었거나 존재하지 않습니다."),

  // Payment 404
  PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_404_002", "결제 내역을 찾을 수 없습니다."),

  // Payment 409
  PAYMENT_ALREADY_COMPLETED(HttpStatus.CONFLICT, "PAYMENT_409_001", "이미 결제가 완료된 예매입니다."),
  PAYMENT_NOT_CANCELABLE(HttpStatus.CONFLICT, "PAYMENT_409_002", "환불 가능한 결제 상태가 아닙니다."),

  // Payment 500
  PAYMENT_REFUND_INCONSISTENT(
      HttpStatus.INTERNAL_SERVER_ERROR,
      "PAYMENT_500_001",
      "취소된 결제의 환불 내역을 찾을 수 없습니다. 관리자에게 문의 바랍니다."),

  // Payment 502
  PAYMENT_APPROVAL_FAILED(HttpStatus.BAD_GATEWAY, "PAYMENT_502_001", "PG사 결제 승인에 실패했습니다."),
  PAYMENT_PG_AUTH_FAILED(
      HttpStatus.BAD_GATEWAY, "PAYMENT_502_002", "PG사 인증에 실패했습니다. 관리자에게 문의 바랍니다."),
  PAYMENT_REFUND_FAILED(HttpStatus.BAD_GATEWAY, "PAYMENT_502_003", "PG사 환불 처리에 실패했습니다."),

  // Payment 503
  PAYMENT_PG_COMMUNICATION_FAILED(
      HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_503_001", "PG사와 통신에 실패했습니다."),

  // Ticket 400
  TICKET_QR_INVALID(HttpStatus.BAD_REQUEST, "TICKET_400_001", "유효하지 않은 QR입니다."),

  // Ticket 401
  TICKET_QR_EXPIRED(HttpStatus.UNAUTHORIZED, "TICKET_401_001", "만료된 QR입니다. 다시 발급받아 주세요."),

  // Ticket 404
  TICKET_NOT_FOUND(HttpStatus.NOT_FOUND, "TICKET_404_001", "입장권을 찾을 수 없습니다."),

  // Ticket 409
  TICKET_NOT_USABLE(HttpStatus.CONFLICT, "TICKET_409_001", "확정된 예매가 아니어서 입장권을 조회할 수 없습니다."),
  TICKET_ALREADY_USED(HttpStatus.CONFLICT, "TICKET_409_002", "이미 입장 처리된 입장권입니다."),

  // Ticket 503
  TICKET_BOOKING_COMMUNICATION_FAILED(
      HttpStatus.SERVICE_UNAVAILABLE, "TICKET_503_001", "예매 정보 조회에 실패했습니다. 잠시 후 다시 시도해 주세요.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String message;

  public String getMessage(String message) {
    return Optional.ofNullable(message)
        .filter(Predicate.not(String::isBlank))
        .orElse(this.getMessage());
  }
}
