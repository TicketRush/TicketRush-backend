package com.ticketrush.boundedcontext.performance.app.support;

/** 캐릭터 구성·한마디의 요청 상한(#650). 등록·수정 요청이 같은 값을 쓰도록 한 곳에 둔다. */
public final class CharacterConstraints {

  /** 캐릭터 구성 JSON의 compact 직렬화 UTF-8 최대 바이트 수. 프론트와 합의한 값(#650)이며 프론트 예시(약 400바이트)의 10배 여유다. */
  public static final int CONFIG_MAX_BYTES = 4096;

  /**
   * 캐릭터 구성 JSON의 최대 중첩 깊이(최상위 객체가 1). MySQL 8 JSON 컬럼은 깊이 100을 넘는 문서를 저장 단계에서
   * 거절하므로(ER_JSON_DOCUMENT_TOO_DEEP) 요청 검증에서 먼저 막는다. 프론트 구조는 2-3단이라 32면 넉넉하다.
   */
  public static final int CONFIG_MAX_DEPTH = 32;

  /** 한마디 최대 글자 수. 프론트 UI 제한과 같다. */
  public static final int MESSAGE_MAX_LENGTH = 50;

  private CharacterConstraints() {}
}
