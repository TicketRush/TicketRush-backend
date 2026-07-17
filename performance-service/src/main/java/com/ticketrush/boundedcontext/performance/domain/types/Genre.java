package com.ticketrush.boundedcontext.performance.domain.types;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Genre {
  MUSICAL("뮤지컬"),
  CONCERT("콘서트"),
  CLASSIC("클래식"),
  JAZZ("재즈"),
  FESTIVAL("페스티벌"),
  BALLET("발레/무용"),
  FANMEETING("팬미팅");

  private final String description;
}
