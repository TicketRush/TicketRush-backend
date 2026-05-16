package com.ticketrush.boundedcontext.user.app.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SocialLoginRequest {

  @JsonProperty("socialId")
  private String socialId;

  @JsonProperty("socialProvider")
  private String socialProvider;

  @JsonProperty("name")
  private String name;

  @JsonProperty("email")
  private String email;

  private String maskValue(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }

    if (value.length() <= 2) {
      return value.charAt(0) + "***";
    }

    return value.substring(0, 2) + "***";
  }

  private String maskEmail(String email) {
    if (email == null || email.isEmpty()) {
      return email;
    }

    int atIndex = email.indexOf("@");

    if (atIndex <= 0) {
      return maskValue(email);
    }

    String localPart = email.substring(0, atIndex);
    String domain = email.substring(atIndex);

    if (localPart.length() <= 2) {
      return localPart.charAt(0) + "***" + domain;
    }

    return localPart.substring(0, 2) + "***" + domain;
  }

  @Override
  public String toString() {
    return "SocialLoginRequest{"
        + "socialId='"
        + maskValue(socialId)
        + '\''
        + ", socialProvider='"
        + socialProvider
        + '\''
        + ", name='"
        + maskValue(name)
        + '\''
        + ", email='"
        + maskEmail(email)
        + '\''
        + '}';
  }
}
