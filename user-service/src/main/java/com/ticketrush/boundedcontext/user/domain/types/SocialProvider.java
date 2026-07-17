package com.ticketrush.boundedcontext.user.domain.types;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SocialProvider {
  KAKAO("카카오"),
  NAVER("네이버"),
  GOOGLE("구글");

  private final String description;
}
