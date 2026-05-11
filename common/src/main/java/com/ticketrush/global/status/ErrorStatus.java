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
  AUTH_GOOGLE_INFO_FAILED(HttpStatus.BAD_REQUEST, "AUTH_400_006", "잘못된 구글 정보입니다."),
  AUTH_NAVER_TOKEN_FAILED(HttpStatus.BAD_REQUEST, "AUTH_400_007", "네이버 토큰 요청에 실패했습니다."),
  AUTH_NAVER_INFO_FAILED(HttpStatus.BAD_REQUEST, "AUTH_400_008", "네이버 사용자 정보 조회에 실패했습니다."),

  // Auth 401
  AUTH_INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_401_001", "유효하지 않은 Refresh Token입니다."),
  AUTH_INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_401_002", "유효하지 않은 Access Token입니다."),
  AUTH_INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_401_003", "유효하지 않은 JWT 토큰입니다."),
  AUTH_EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_401_004", "만료된 JWT 토큰입니다."),
  AUTH_INVALID_TOKEN_TYPE(HttpStatus.UNAUTHORIZED, "AUTH_401_005", "Access Token만 사용할 수 있습니다."),

  // Auth 403
  AUTH_ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_403_001", "접근 권한이 없습니다."),

  // Auth 404
  AUTH_KAKAO_INFO_FAILED(HttpStatus.NOT_FOUND, "AUTH_404_001", "카카오 사용자 정보 조회를 실패하였습니다."),

  // Auth 500
  AUTH_USER_SERVER_ERROR(
      HttpStatus.INTERNAL_SERVER_ERROR, "AUTH_500_001", "user-service 내부 오류가 발생했습니다."),

  // Auth 503
  AUTH_USER_COMMUNICATION_FAILED(
      HttpStatus.SERVICE_UNAVAILABLE, "AUTH_503_001", "user-service와 통신에 실패했습니다."),

  // Booking 404
  BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "BOOKING_404_001", "해당 예매를 찾을 수 없습니다."),

  // Booking 409
  BOOKING_CANCEL_NOT_ALLOWED(HttpStatus.CONFLICT, "BOOKING_409_001", "취소할 수 없는 예매 상태입니다."),

  // Booking 500
  BOOKING_NUMBER_RETRY_EXCEEDED(
      HttpStatus.INTERNAL_SERVER_ERROR, "BOOKING_500_001", "재시도 횟수를 초과하여 고유한 예약 번호를 생성할 수 없습니다."),

  // Seat 400
  SEAT_HOLD_TIME_INVALID(HttpStatus.BAD_REQUEST, "SEAT_400_001", "선점 만료 시간은 현재 시간 이후여야 합니다."),

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

  // User 404
  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_404_001", "해당 사용자를 찾을 수 없습니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String message;

  public String getMessage(String message) {
    return Optional.ofNullable(message)
        .filter(Predicate.not(String::isBlank))
        .orElse(this.getMessage());
  }
}
