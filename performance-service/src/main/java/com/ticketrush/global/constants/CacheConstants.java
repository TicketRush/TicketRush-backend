package com.ticketrush.global.constants;

/** performance-service 캐시 이름 상수. 키 네이밍은 Redis 키 컨벤션 통일(#425) 확정 전 임시이며, 확정 시 이관한다. */
public final class CacheConstants {

  public static final String PERFORMANCE_LIST_CACHE = "performance:list";

  private CacheConstants() {}
}
