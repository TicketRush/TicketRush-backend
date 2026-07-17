package com.ticketrush.global.status;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum SuccessStatus {
  OK(HttpStatus.OK, "COMMON_200", "성공입니다."),
  CREATED(HttpStatus.CREATED, "COMMON_201", "리소스가 성공적으로 생성되었습니다."),
  NO_CONTENT(HttpStatus.NO_CONTENT, "COMMON_204", "요청이 성공적으로 처리되었습니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String message;
}
