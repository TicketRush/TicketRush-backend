package com.ticketrush.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListSlice;
import com.ticketrush.boundedcontext.performance.domain.policy.PerformanceShowTimePolicy;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.global.types.PerformanceStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;

/**
 * 공연 목록 캐시 값의 Redis 왕복 계약 (#671).
 *
 * <p><b>왜 별도로 고정하는가.</b> {@code show_at}은 이 캐시 타입에 들어간 <b>첫 {@code LocalDateTime}</b>이고, 응답에 오프셋을
 * 붙이려고 전용 직렬화기를 달았다. 캐시는 {@link CacheConfig}가 {@code JacksonJsonRedisSerializer}로 값을 쓰고 읽는데, 쓸 때는
 * 필드의 {@code @JsonSerialize}가 적용되므로 오프셋이 붙은 문자열이 저장된다 — 읽을 때 그 문자열을 되돌리지 못하면 왕복이 깨진다.
 *
 * <p><b>깨져도 조용하다.</b> {@code CacheConfig}의 {@code FailOpenCacheErrorHandler}가 캐시 조회 예외를 삼키고 미스로
 * 취급하므로, 왕복이 깨지면 예외가 아니라 <b>영구 캐시 미스</b>로 나타난다. 기능은 멀쩡해 보이고 DB 부하만 늘어 이 테스트 없이는 발견이 늦는다.
 *
 * <p>실제 캐시 동작은 {@code PerformanceListCacheTest}가 보지만 그쪽은 Testcontainers라 Docker 없는 환경에서 돌지 않는다. 이
 * 테스트는 직렬화기만 떼어내 같은 계약을 Docker 없이 고정한다.
 */
class PerformanceListCacheSerializationTest {

  private static final LocalDate SHOW_DATE = LocalDate.of(2027, 1, 10);
  private static final LocalTime SHOW_TIME = LocalTime.of(19, 0);

  private static PerformanceListSlice slice() {
    return new PerformanceListSlice(
        List.of(
            new PerformanceListResponse(
                1L,
                "공연",
                "출연자",
                Genre.MUSICAL,
                SHOW_DATE,
                SHOW_TIME,
                PerformanceShowTimePolicy.showAt(SHOW_DATE, SHOW_TIME),
                "서울",
                "https://s3.example.com/main.jpg",
                PerformanceStatus.ON_SALE,
                50000L,
                500L,
                245L)),
        true);
  }

  @Test
  @DisplayName("성공: 목록 캐시 값이 Redis 직렬화기로 왕복해도 show_at 이 보존된다 (#671)")
  void performanceListSliceSurvivesCacheRoundTrip() {
    // given — CacheConfig 가 캐시 값에 쓰는 것과 같은 직렬화기다.
    JacksonJsonRedisSerializer<PerformanceListSlice> serializer =
        new JacksonJsonRedisSerializer<>(PerformanceListSlice.class);

    // when
    byte[] written = serializer.serialize(slice());
    PerformanceListSlice read = serializer.deserialize(written);

    // then — 읽어낸 값이 쓴 값과 같아야 캐시 히트가 원본과 구분되지 않는다.
    assertThat(read).isNotNull();
    assertThat(read.hasNext()).isTrue();
    assertThat(read.content()).hasSize(1);

    PerformanceListResponse row = read.content().getFirst();
    assertThat(row.showAt()).isEqualTo(PerformanceShowTimePolicy.showAt(SHOW_DATE, SHOW_TIME));
    assertThat(row.showDate()).isEqualTo(SHOW_DATE);
    assertThat(row.showTime()).isEqualTo(SHOW_TIME);
    assertThat(row.performanceStatus()).isEqualTo(PerformanceStatus.ON_SALE);
    assertThat(row.totalSeats()).isEqualTo(500L);
  }
}
