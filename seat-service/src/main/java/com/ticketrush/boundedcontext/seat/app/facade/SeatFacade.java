package com.ticketrush.boundedcontext.seat.app.facade;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatAdminMonitoringResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatAdminSeatDetailResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatMapItemResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatNumberResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsResponse;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusStreamSubscriber;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatAdminForceReleaseHoldUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatConfirmSoldUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatCreateDefaultLayoutUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatGetAdminSeatDetailUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatGetNumbersUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatGetSeatMapUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatGetStatusCountsUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatHoldUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatLockUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatReleaseHoldUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatUnlockUseCase;
import com.ticketrush.boundedcontext.seat.out.repository.SeatLayoutRepository;
import com.ticketrush.boundedcontext.seat.out.repository.SeatMapCacheRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.seat.event.SeatHoldFailedEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatFacade {

  private final SeatGetStatusCountsUseCase seatGetStatusCountsUseCase;
  private final SeatGetSeatMapUseCase seatGetSeatMapUseCase;
  private final SeatGetNumbersUseCase seatGetNumbersUseCase;
  private final SeatGetAdminSeatDetailUseCase seatGetAdminSeatDetailUseCase;
  private final SeatAdminForceReleaseHoldUseCase seatAdminForceReleaseHoldUseCase;
  private final SeatCreateDefaultLayoutUseCase seatCreateDefaultLayoutUseCase;
  private final SeatConfirmSoldUseCase seatConfirmSoldUseCase;
  private final SeatReleaseHoldUseCase seatReleaseHoldUseCase;
  private final SeatHoldUseCase seatHoldUseCase;
  private final SeatLockUseCase seatLockUseCase;
  private final SeatUnlockUseCase seatUnlockUseCase;
  private final SeatStatusStreamSubscriber seatStatusStreamSubscriber;
  private final SeatLayoutRepository seatLayoutRepository;
  private final EventPublisher eventPublisher;
  private final SeatMapCacheRepository seatMapCacheRepository;
  private final JsonConverter jsonConverter;

  /**
   * 좌석맵을 응답 본문에 그대로 실을 JSON 배열 문자열로 반환한다(#469).
   *
   * <p>DTO 리스트가 아니라 직렬화된 JSON을 캐싱하는 이유: 이 경로의 병목이 DB가 아니라 CPU(직렬화 포함)라는 실측(#509·#518) 때문이다. 히트는 DB
   * 커넥션·JPA 매핑·Jackson 직렬화를 전부 건너뛰고, 캐시 확인이 {@code SeatGetSeatMapUseCase}의 트랜잭션 밖에 있어 히트 시 커넥션 풀도
   * 건드리지 않는다. 미스에서도 직렬화는 여기 1회뿐이며 그 결과를 캐시와 응답에 같이 쓴다. 무효화는 {@code
   * SeatStatusEventPublisher#publishAfterCommit}이 담당한다.
   */
  public String getPerformanceSeatMap(Long performanceId) {
    String cached = seatMapCacheRepository.get(performanceId);
    if (cached != null) {
      return cached;
    }

    List<SeatMapItemResponse> seatMap = seatGetSeatMapUseCase.execute(performanceId);
    // 공용 JsonConverter는 MVC 응답과 같은 auto-configured ObjectMapper를 쓰므로
    // snake_case·날짜 포맷이 기존 응답과 동일하다.
    String json = jsonConverter.serialize(seatMap);

    // 좌석 생성 전의 빈 배열을 캐싱하면 생성 직후 TTL까지 빈 좌석맵이 보이므로 캐시하지 않는다
    // (생성 경로는 상태 변경 발행을 안 거쳐 evict로 걷어낼 수 없다).
    if (!seatMap.isEmpty()) {
      seatMapCacheRepository.set(performanceId, json);
    }

    return json;
  }

  public List<SeatNumberResponse> getSeatNumbers(List<Long> seatIds) {
    return seatGetNumbersUseCase.execute(seatIds);
  }

  /**
   * 관리자 좌석 현황 모니터링 (#562). 요약과 좌석 맵을 한 응답으로 묶는다.
   *
   * <p><b>좌석 맵 캐시({@link #getPerformanceSeatMap})를 경유하지 않는다.</b> 그 캐시는 TTL 30초인데 관리자 화면의 갱신 정책이
   * "새로고침 버튼·관리자 작업 실행·좌석 선택"이라, 관리자가 방금 강제 해제한 좌석이 최대 30초간 HOLD로 보이는 것은 그대로 결함이 된다. 캐시가 막으려던 것은
   * 오픈런 트래픽의 CPU 비용인데(#469) 관리자 경로는 그 규모가 아니다.
   */
  public SeatAdminMonitoringResponse getAdminMonitoring(Long performanceId) {
    return new SeatAdminMonitoringResponse(
        seatGetStatusCountsUseCase.execute(performanceId),
        seatGetSeatMapUseCase.execute(performanceId));
  }

  public SeatAdminSeatDetailResponse getAdminSeatDetail(Long performanceId, Long seatId) {
    return seatGetAdminSeatDetailUseCase.execute(performanceId, seatId);
  }

  /** 관리자의 HOLD 좌석 강제 해제 (#562). 예매 정합은 유스케이스가 발행하는 {@code SeatHoldExpiredEvent}가 맡는다. */
  public void forceReleaseHold(
      Long adminId, Long performanceId, Long seatId, String expectedBookingNumber) {
    seatAdminForceReleaseHoldUseCase.execute(adminId, performanceId, seatId, expectedBookingNumber);
  }

  public SeatStatusCountsResponse getPerformanceSeatStatusCounts(Long performanceId) {
    return seatGetStatusCountsUseCase.execute(performanceId);
  }

  public SseEmitter subscribeSeatStatus(Long performanceId) {
    return seatStatusStreamSubscriber.subscribe(performanceId);
  }

  public void createDefaultSeats(Long performanceId) {
    try {
      seatCreateDefaultLayoutUseCase.execute(performanceId);
    } catch (DataIntegrityViolationException e) {
      if (seatLayoutRepository.existsByPerformanceId(performanceId)) {
        log.info("동시에 생성된 좌석 배치도가 있어 좌석 생성을 스킵합니다. performanceId: {}", performanceId);
        return;
      }

      throw e;
    }
  }

  public void confirmSold(String bookingNumber, Long seatId) {
    seatConfirmSoldUseCase.execute(bookingNumber, seatId);
  }

  /** PENDING 예매가 즉시 취소돼 선점 좌석을 만료 전에 되돌린다 (#559). */
  public void releaseHold(String bookingNumber, Long seatId) {
    seatReleaseHoldUseCase.execute(bookingNumber, seatId);
  }

  /**
   * 좌석 선점 오케스트레이션. 상위 {@code BookingCreatedEventListener}의 {@code InboxService.runIfFirst} 트랜잭션 안에서
   * 실행되어, 처리 결과(HOLD 또는 보상 이벤트 발행)와 Inbox 기록이 한 커밋으로 원자화된다. Redisson 락은 이 트랜잭션과 별개인 Redis 부수효과이므로
   * 실패 경로에서 명시적으로 해제한다.
   */
  public void tryLockSeat(Long bookingId, String bookingNumber, Long seatId, Long userId) {
    // 1. Redis 락 시도
    Optional<LocalDateTime> holdExpiredAtOpt = seatLockUseCase.execute(seatId, userId);

    // 2-B. 락 실패(다른 처리가 선점 중): DB 변경이 없어 트랜잭션이 깨끗하므로 보상 이벤트를 Outbox로 발행한다.
    if (holdExpiredAtOpt.isEmpty()) {
      publishCompensationEvent(bookingId, seatId, "이미 선점된 좌석입니다(락 획득 실패).");
      return;
    }

    boolean held;
    try {
      // 2-A. 성공: Seat DB 상태를 HOLD로 업데이트(상위 runIfFirst 트랜잭션에 조인 → HOLD와 Inbox 기록이 원자 커밋).
      // 미가용(이미 선점/판매) 좌석은 예외 대신 false를 반환해 트랜잭션을 오염시키지 않는다(아래에서 보상 발행).
      held = seatHoldUseCase.execute(seatId, holdExpiredAtOpt.get(), bookingNumber);
    } catch (RuntimeException e) {
      // 일시(인프라) DB 오류: 상위 트랜잭션이 rollback-only가 되어 같은 트랜잭션에서 보상 발행이 불가하다.
      // 획득한 Redis 락만 해제하고 예외를 상위(runIfFirst)로 전파해 롤백시키고 #269 분기(일시=재소비)에 위임한다.
      log.error("좌석 DB 업데이트 중 일시 오류 발생. Redis 락 해제 후 재소비에 위임. seatId: {}", seatId, e);
      seatUnlockUseCase.execute(seatId);
      throw e;
    }

    if (!held) {
      // 2-C. 미가용(이미 선점/판매됨): 예외가 아니라 트랜잭션이 깨끗하다. 락 해제 후 보상 이벤트를 Outbox로
      // 발행해 Inbox 기록과 원자적으로 커밋한다(booking 모듈의 보상 트랜잭션 트리거, 즉시 보상 보장).
      log.warn("좌석이 이미 선점/판매되어 HOLD 불가. Redis 락 해제 및 보상 이벤트 발행. seatId: {}", seatId);
      seatUnlockUseCase.execute(seatId);
      publishCompensationEvent(bookingId, seatId, "이미 선점/판매된 좌석입니다.");
    }
  }

  /**
   * 좌석 선점 실패 보상 이벤트를 Outbox로 발행한다.
   *
   * <p>{@code OutboxEventPublisher}가 호출부(상위 runIfFirst)의 활성 트랜잭션에 참여해 {@code OutboxEntity}로 저장하므로,
   * 비즈니스 처리·Inbox 기록과 함께 원자적으로 커밋된다. 저장(INSERT) 실패는 트랜잭션 롤백으로 이어져 재소비로 보존된다.
   */
  private void publishCompensationEvent(Long bookingId, Long seatId, String reason) {
    eventPublisher.publish(new SeatHoldFailedEvent(bookingId, seatId, reason));
    log.info("보상 이벤트(SeatHoldFailedEvent) 발행(Outbox 기록) 완료. bookingId: {}", bookingId);
  }
}
