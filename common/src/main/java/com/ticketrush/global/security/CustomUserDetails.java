package com.ticketrush.global.security;

import lombok.Getter;

// 로그인한 사용자 정보 담는 객체
@Getter
public class CustomUserDetails {

  private final Long userId;
  private final String role;

  public CustomUserDetails(Long userId, String role) {
    this.userId = userId;
    this.role = role;
  }
}
