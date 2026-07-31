package com.ticketrush.queue;

import com.ticketrush.exception.BusinessException;
import com.ticketrush.queue.dto.EnqueueResponse;
import com.ticketrush.queue.dto.WaitingStatusResponse;
import com.ticketrush.status.ErrorStatus;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 대기열 진입·상태 확인(ADR 0009 §2·§3·§4).
 *
 * <p><b>상태 확인 경로가 이 클래스의 설계 제약이다.</b> 1만 명이 25초마다 두드리는 경로라 ADR §4가 요구한 것 — JWT 검증 체인 없음, DB 없음, 요청당
 * 로그 없음, 응답 최소 — 을 전부 지켜야 폴링 주기 다이얼이 의미를 갖는다. 폴링 1회의 Redis 명령은 <b>GET(대기 토큰) + ZRANK 두 번</b>이고, 그
 * 외는 전부 로컬 캐시에서 답한다.
 *
 * <p>승급자를 ZSET에서 제거하지 않는다. 제거하면 재폴링 때 순번을 다시 읽을 수 없어 "승급 응답을 놓친 사용자"가 영영 입장 토큰을 못 받는다. 남겨 두면 임계치가
 * 시간에 선형으로 계속 올라가므로 판정이 멱등해지고, 대기 인원은 {@code 총 진입 - 허용 누적}으로 계산된다. 잔류는 키 TTL이 청소한다.
 *
 * <p>Redis 장애·타임아웃은 전부 <b>fail-closed</b>(503)다 — ADR 0008. 대기열만 fail-open으로 두는 것은 오픈 시각에 유입 제어가
 * 통째로 사라진다는 뜻이라 택하지 않는다.
 */
@Slf4j
@Service
@EnableConfigurationProperties(WaitingRoomProperties.class)
public class WaitingRoomService {

  private final ReactiveStringRedisTemplate redis;
  private final WaitingRoomProperties properties;
  private final WaitingRoomMetrics metrics;

  /**
   * 공연별 대기열 개시 시각 캐시.
   *
   * <p>게이트웨이 인스턴스는 하나뿐이고(단일 EC2, ADR 0006) 값이 한 번 정해지면 불변이라 폴링마다 Redis를 두드릴 이유가 없다. 재시작 후 첫 요청만
   * Redis를 탄다.
   */
  private final Map<Long, Long> openedAtCache = new ConcurrentHashMap<>();

  /** 공연별 총 진입 인원(ZCARD) 캐시. 25초 다이얼에 수 초 낡은 값은 무해하다. */
  private final Map<Long, CachedCount> enqueuedCountCache = new ConcurrentHashMap<>();

  public WaitingRoomService(
      ReactiveStringRedisTemplate redis,
      WaitingRoomProperties properties,
      WaitingRoomMetrics metrics) {
    this.redis = redis;
    this.properties = properties;
    this.metrics = metrics;
  }

  /**
   * 대기열 진입 — 순번을 발급하고 불투명 대기 토큰을 돌려준다.
   *
   * <p>1인 1회라 여기서만 JWT를 태운다(호출부 {@code WaitingRoomController}). 재진입해도 {@code ZADD NX} 라 최초 순번이
   * 유지된다.
   */
  public Mono<EnqueueResponse> enqueue(Long performanceId, Long userId) {
    String waitingToken = newToken();
    long now = System.currentTimeMillis();
    String waitingKey = WaitingRoomKey.waiting(performanceId);

    return registerIfAbsent(waitingKey, userId, now)
        .then(redis.expire(waitingKey, properties.waitingTtl()))
        .then(
            redis
                .opsForValue()
                .setIfAbsent(
                    WaitingRoomKey.openedAt(performanceId),
                    String.valueOf(now),
                    properties.waitingTtl()))
        .then(
            redis
                .opsForValue()
                .set(
                    WaitingRoomKey.waitingToken(waitingToken),
                    performanceId + ":" + userId,
                    properties.waitingTtl()))
        // 진입은 ZCARD 캐시를 무효화한다 — 방금 늘어난 인원이 응답에 반영되지 않으면 첫 화면부터 어긋난다.
        .doOnSuccess(ignored -> enqueuedCountCache.remove(performanceId))
        .then(snapshot(performanceId, userId))
        .map(
            snapshot ->
                new EnqueueResponse(
                    waitingToken, snapshot.rank(), snapshot.waiting(), snapshot.pollSeconds()))
        .onErrorMap(WaitingRoomService::isInfrastructureFailure, this::toUnavailable);
  }

  /**
   * 상태 확인 — 순번·대기 인원·다음 폴링 시각을 돌려주고, 허용선 안이면 입장 토큰을 발급한다.
   *
   * <p>입장 토큰은 대기 토큰과 같은 문자열을 쓴다. 별도 값을 새로 만들면 재폴링 시 이전에 발급된 토큰을 되찾을 방법이 없어, 승급 응답을 한 번 놓친 사용자가 입장하지
   * 못한다. 같은 값이면 {@code SET} 이 멱등하다(키 prefix가 달라 레코드는 별개다).
   */
  public Mono<WaitingStatusResponse> status(Long performanceId, String waitingToken) {
    if (waitingToken == null || waitingToken.isBlank()) {
      return Mono.error(new BusinessException(ErrorStatus.QUEUE_WAITING_TOKEN_REQUIRED));
    }

    return redis
        .opsForValue()
        .get(WaitingRoomKey.waitingToken(waitingToken))
        .switchIfEmpty(Mono.error(new BusinessException(ErrorStatus.QUEUE_WAITING_TOKEN_REQUIRED)))
        .flatMap(owner -> resolveOwner(owner, performanceId))
        .flatMap(userId -> snapshot(performanceId, userId).map(s -> Tuple.of(userId, s)))
        .flatMap(t -> respond(waitingToken, t.userId(), t.snapshot()))
        .onErrorMap(WaitingRoomService::isInfrastructureFailure, this::toUnavailable);
  }

  private Mono<WaitingStatusResponse> respond(String waitingToken, long userId, Snapshot snapshot) {
    if (!snapshot.admitted()) {
      metrics.recordWaiting(snapshot.waiting(), snapshot.pollSeconds());
      return Mono.just(
          WaitingStatusResponse.waiting(
              snapshot.rank(), snapshot.waiting(), snapshot.pollSeconds()));
    }

    return redis
        .opsForValue()
        .set(
            WaitingRoomKey.entryToken(waitingToken),
            String.valueOf(userId),
            properties.entryTokenTtl())
        .thenReturn(
            WaitingStatusResponse.admitted(
                snapshot.waiting(), snapshot.pollSeconds(), waitingToken))
        .doOnNext(ignored -> metrics.recordAdmitted(snapshot.waiting(), snapshot.pollSeconds()));
  }

  /** 대기 토큰 레코드({@code {performanceId}:{userId}})가 요청한 공연의 것인지 확인하고 userId를 꺼낸다. */
  private Mono<Long> resolveOwner(String owner, Long performanceId) {
    int separator = owner.indexOf(':');
    if (separator < 0) {
      return Mono.error(new BusinessException(ErrorStatus.QUEUE_WAITING_TOKEN_REQUIRED));
    }
    try {
      long tokenPerformanceId = Long.parseLong(owner.substring(0, separator));
      long userId = Long.parseLong(owner.substring(separator + 1));
      if (tokenPerformanceId != performanceId) {
        return Mono.error(new BusinessException(ErrorStatus.QUEUE_WAITING_TOKEN_REQUIRED));
      }
      return Mono.just(userId);
    } catch (NumberFormatException e) {
      return Mono.error(new BusinessException(ErrorStatus.QUEUE_WAITING_TOKEN_REQUIRED));
    }
  }

  private Mono<Snapshot> snapshot(Long performanceId, long userId) {
    return openedAt(performanceId)
        .flatMap(
            openedAt ->
                Mono.zip(rank(performanceId, userId), enqueuedCount(performanceId))
                    .map(t -> toSnapshot(t.getT1(), t.getT2(), openedAt)));
  }

  private Snapshot toSnapshot(long rank, long enqueued, long openedAt) {
    long threshold =
        WaitingRoomPolicy.admittedThreshold(
            System.currentTimeMillis(), openedAt, properties.admitRatePerSecond());
    long waiting = WaitingRoomPolicy.remainingWaiting(enqueued, threshold);
    int pollSeconds =
        WaitingRoomPolicy.pollSeconds(
            waiting,
            properties.statusRpsCapacity(),
            properties.minPollSeconds(),
            properties.maxPollSeconds());
    // 킬 스위치가 꺼져 있으면 대기열은 존재하지 않는 것과 같다 — 전원 즉시 입장이다.
    boolean admitted = !properties.enabled() || WaitingRoomPolicy.admitted(rank, threshold);
    return new Snapshot(rank, waiting, pollSeconds, admitted);
  }

  /**
   * 이미 줄에 서 있으면 score를 건드리지 않는다 — 새로고침이 순번을 뒤로 밀면 대기열이 아니라 벌칙이다.
   *
   * <p>{@code ReactiveZSetOperations} 에 {@code ZADD NX} 가 없어 조회 후 추가한다. 같은 사용자의 동시 진입 요청이 겹치면 둘 다
   * 추가로 갈 수 있지만 member가 같아 결과는 마지막 score 하나이고, 그 폭은 두 요청의 시간차(수 ms)다. 진입은 1인 1회라 명령이 하나 느는 비용도 폴링
   * 경로와 달리 무해하다.
   */
  private Mono<Boolean> registerIfAbsent(String waitingKey, long userId, long now) {
    String member = String.valueOf(userId);
    return redis
        .opsForZSet()
        .rank(waitingKey, member)
        .defaultIfEmpty(-1L)
        .flatMap(
            existing ->
                existing >= 0L
                    ? Mono.just(false)
                    : redis.opsForZSet().add(waitingKey, member, now));
  }

  private Mono<Long> rank(Long performanceId, long userId) {
    return redis
        .opsForZSet()
        .rank(WaitingRoomKey.waiting(performanceId), String.valueOf(userId))
        .defaultIfEmpty(-1L);
  }

  private Mono<Long> openedAt(Long performanceId) {
    Long cached = openedAtCache.get(performanceId);
    if (cached != null) {
      return Mono.just(cached);
    }
    return redis
        .opsForValue()
        .get(WaitingRoomKey.openedAt(performanceId))
        .map(Long::parseLong)
        .doOnNext(value -> openedAtCache.put(performanceId, value))
        // 개시 시각이 없으면 대기열 TTL이 지났거나 진입한 적이 없다. 다시 진입하게 한다.
        .switchIfEmpty(Mono.error(new BusinessException(ErrorStatus.QUEUE_WAITING_TOKEN_REQUIRED)));
  }

  private Mono<Long> enqueuedCount(Long performanceId) {
    long now = System.currentTimeMillis();
    CachedCount cached = enqueuedCountCache.get(performanceId);
    if (cached != null
        && now - cached.fetchedAtMs() < properties.waitingCountCacheTtl().toMillis()) {
      return Mono.just(cached.value());
    }
    return redis
        .opsForZSet()
        .size(WaitingRoomKey.waiting(performanceId))
        .defaultIfEmpty(0L)
        .doOnNext(value -> enqueuedCountCache.put(performanceId, new CachedCount(value, now)));
  }

  private static String newToken() {
    // 122비트 SecureRandom. 커스텀 생성기를 만들 이유가 없고, JWT를 쓰지 않는 이유는 ADR 0009 기각안 4.
    return UUID.randomUUID().toString().replace("-", "");
  }

  /** 도메인 판정(403)이 아닌 것은 전부 인프라 실패로 본다 — Redis 다운·타임아웃·직렬화 오류. */
  private static boolean isInfrastructureFailure(Throwable e) {
    return !(e instanceof BusinessException);
  }

  private BusinessException toUnavailable(Throwable e) {
    metrics.recordStatusUnavailable();
    // 요청당 로그는 남기지 않는다(ADR §4)는 원칙의 예외다 — 장애 구간에서만 찍히고, 이게 없으면 fail-closed의 원인을 못 가린다.
    log.warn("대기열 Redis 접근 실패 — fail-closed로 거절한다(ADR 0008).", e);
    return new BusinessException(ErrorStatus.QUEUE_UNAVAILABLE);
  }

  private record Snapshot(long rank, long waiting, int pollSeconds, boolean admitted) {}

  private record CachedCount(long value, long fetchedAtMs) {}

  private record Tuple(long userId, Snapshot snapshot) {
    static Tuple of(long userId, Snapshot snapshot) {
      return new Tuple(userId, snapshot);
    }
  }
}
