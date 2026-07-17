package com.ticketrush.global.exception;

import com.ticketrush.global.status.ErrorStatus;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

  private final ErrorStatus errorStatus;
  private final Object data;

  // 메세지만
  public BusinessException(String message) {
    super(message);
    this.errorStatus = ErrorStatus.INTERNAL_SERVER_ERROR;
    this.data = null;
  }

  // 메세지 + 원인 - 개발자용 메세지
  public BusinessException(String message, Throwable cause) {
    super(message, cause);
    this.errorStatus = ErrorStatus.INTERNAL_SERVER_ERROR;
    this.data = null;
  }

  // ErrorStatus 기반
  public BusinessException(ErrorStatus errorStatus) {
    super(errorStatus.getMessage());
    this.errorStatus = errorStatus;
    this.data = null;
  }

  // ErrorStatus + 추가 데이터
  public BusinessException(ErrorStatus errorStatus, Object data) {
    super(errorStatus.getMessage());
    this.errorStatus = errorStatus;
    this.data = data;
  }

  // ErrorStatus + cause
  public BusinessException(ErrorStatus errorStatus, Throwable cause) {
    super(errorStatus.getMessage(), cause);
    this.errorStatus = errorStatus;
    this.data = null;
  }
}
