package com.ticketrush.boundedcontext.auth.app.dto.response.social;

import com.fasterxml.jackson.annotation.JsonProperty;

// user-service → auth-service 응답 DTO
public record UserServiceSocialLoginResponse(
    @JsonProperty(value = "user_id") Long userId,
    String name,
    @JsonProperty(value = "is_new_user") boolean isNewUser) {

  private String maskValue(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }

    if (value.length() <= 2) {
      return value.charAt(0) + "***";
    }

    return value.substring(0, 2) + "***";
  }

  @Override
  public String toString() {
    return "UserServiceSocialLoginResponse{"
        + "userId="
        + userId
        + ", name='"
        + maskValue(name)
        + '\''
        + ", isNewUser="
        + isNewUser
        + '}';
  }
}
