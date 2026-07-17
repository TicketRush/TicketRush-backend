package com.ticketrush.boundedcontext.user.domain.types;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserRole {
  MEMBER("일반 회원"),
  ADMIN("관리자");

  private final String description;
}
