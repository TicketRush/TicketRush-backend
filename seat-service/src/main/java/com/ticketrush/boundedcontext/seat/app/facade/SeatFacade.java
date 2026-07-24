package com.ticketrush.boundedcontext.seat.app.facade;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatMapItemResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatNumberResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsResponse;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusStreamSubscriber;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatConfirmSoldUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatCreateDefaultLayoutUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatGetNumbersUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatGetSeatMapUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatGetStatusCountsUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatHoldUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatLockUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatUnlockUseCase;
import com.ticketrush.boundedcontext.seat.out.repository.SeatLayoutRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
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
  private final SeatCreateDefaultLayoutUseCase seatCreateDefaultLayoutUseCase;
  private final SeatConfirmSoldUseCase seatConfirmSoldUseCase;
  private final SeatHoldUseCase seatHoldUseCase;
  private final SeatLockUseCase seatLockUseCase;
  private final SeatUnlockUseCase seatUnlockUseCase;
  private final SeatStatusStreamSubscriber seatStatusStreamSubscriber;
  private final SeatLayoutRepository seatLayoutRepository;
  private final EventPublisher eventPublisher;

  public List<SeatMapItemResponse> getPerformanceSeatMap(Long performanceId) {
    return seatGetSeatMapUseCase.execute(performanceId);
  }

  public List<SeatNumberResponse> getSeatNumbers(List<Long> seatIds) {
    return seatGetNumbersUseCase.execute(seatIds);
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
