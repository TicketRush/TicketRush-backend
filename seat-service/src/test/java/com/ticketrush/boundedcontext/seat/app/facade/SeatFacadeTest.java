package com.ticketrush.boundedcontext.seat.app.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatMapItemResponse;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatGetSeatMapUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatHoldUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatLockUseCase;
import com.ticketrush.boundedcontext.seat.app.usecase.SeatUnlockUseCase;
import com.ticketrush.boundedcontext.seat.out.repository.SeatMapCacheRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.types.SeatStatus;
import com.ticketrush.shared.seat.event.SeatHoldFailedEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class SeatFacadeTest {

  @InjectMocks private SeatFacade seatFacade;

  @Mock private SeatLockUseCase seatLockUseCase;
  @Mock private SeatHoldUseCase seatHoldUseCase;
  @Mock private SeatUnlockUseCase seatUnlockUseCase;
  @Mock private EventPublisher eventPublisher;
  @Mock private SeatGetSeatMapUseCase seatGetSeatMapUseCase;
  @Mock private SeatMapCacheRepository seatMapCacheRepository;

  private static final Long BOOKING_ID = 10L;
  private static final String BOOKING_NUMBER = "BOOK-1234";
  private static final Long SEAT_ID = 3L;
  private static final Long USER_ID = 4L;
  private static final Long PERFORMANCE_ID = 1L;

  /**
   * 좌석맵 캐시 흐름 테스트용 파사드. 직렬화 흐름을 실제로 태우기 위해 JsonConverter는 mock이 아닌 실물을 쓴다 — ponytail
   * 선례(SeatHoldConcurrencyTest)대로 좌석맵 경로가 쓰는 의존성만 채우고 나머지는 null로 둔다.
   */
  private SeatFacade seatMapFacade() {
    return new SeatFacade(
        null,
        seatGetSeatMapUseCase,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        seatMapCacheRepository,
        new JsonConverter(JsonMapper.builder().build()));
  }

  @Test
  @DisplayName("좌석맵 캐시 히트: DB 유스케이스를 호출하지 않고 캐시된 JSON을 그대로 반환한다")
  void getPerformanceSeatMap_cacheHit_skipsDb() {
    // given
    String cachedJson = "[{\"seat_id\":1}]";
    given(seatMapCacheRepository.get(PERFORMANCE_ID)).willReturn(cachedJson);

    // when
    String result = seatMapFacade().getPerformanceSeatMap(PERFORMANCE_ID);

    // then — 완료 조건 "캐시 히트 시 DB를 거치지 않는다"(#469)
    assertThat(result).isEqualTo(cachedJson);
    verifyNoInteractions(seatGetSeatMapUseCase);
  }

  @Test
  @DisplayName("좌석맵 캐시 미스: DB 조회 결과를 직렬화해 캐시에 적재하고 같은 JSON을 반환한다")
  void getPerformanceSeatMap_cacheMiss_populatesCache() {
    // given
    given(seatMapCacheRepository.get(PERFORMANCE_ID)).willReturn(null);
    given(seatGetSeatMapUseCase.execute(PERFORMANCE_ID))
        .willReturn(List.of(new SeatMapItemResponse(1L, 101L, "A-1", SeatStatus.AVAILABLE, null)));

    // when
    String result = seatMapFacade().getPerformanceSeatMap(PERFORMANCE_ID);

    // then — 응답과 캐시가 같은 직렬화 1회의 산물이다
    assertThat(result).contains("A-1").contains("AVAILABLE");
    verify(seatMapCacheRepository).set(PERFORMANCE_ID, result);
  }

  @Test
  @DisplayName("좌석맵이 빈 리스트면 캐시에 적재하지 않는다(좌석 생성 전 stale 빈 응답 방지)")
  void getPerformanceSeatMap_emptyList_notCached() {
    // given
    given(seatMapCacheRepository.get(PERFORMANCE_ID)).willReturn(null);
    given(seatGetSeatMapUseCase.execute(PERFORMANCE_ID)).willReturn(List.of());

    // when
    String result = seatMapFacade().getPerformanceSeatMap(PERFORMANCE_ID);

    // then — 좌석 생성 경로는 상태 변경 발행을 안 거쳐 evict로 걷어낼 수 없으므로 애초에 적재하지 않는다
    assertThat(result).isEqualTo("[]");
    verify(seatMapCacheRepository, never()).set(anyLong(), anyString());
  }

  @Test
  @DisplayName("락 획득 + HOLD 성공: 락 해제·보상 발행 없이 정상 완료한다")
  void tryLockSeat_success() {
    // given
    LocalDateTime holdExpiredAt = LocalDateTime.now().plusMinutes(5);
    given(seatLockUseCase.execute(SEAT_ID, USER_ID)).willReturn(Optional.of(holdExpiredAt));
    given(seatHoldUseCase.execute(SEAT_ID, holdExpiredAt, BOOKING_NUMBER)).willReturn(true);

    // when
    seatFacade.tryLockSeat(BOOKING_ID, BOOKING_NUMBER, SEAT_ID, USER_ID);

    // then
    verify(seatUnlockUseCase, never()).execute(anyLong());
    verify(eventPublisher, never()).publish(any());
  }

  @Test
  @DisplayName("락 획득 + 미가용(HOLD 불가): 락을 해제하고 보상 이벤트를 Outbox로 발행한다(즉시 보상)")
  void tryLockSeat_notAvailable_publishesCompensation() {
    // given: seatHoldUseCase가 예외 대신 false 반환(이미 선점/판매)
    LocalDateTime holdExpiredAt = LocalDateTime.now().plusMinutes(5);
    given(seatLockUseCase.execute(SEAT_ID, USER_ID)).willReturn(Optional.of(holdExpiredAt));
    given(seatHoldUseCase.execute(SEAT_ID, holdExpiredAt, BOOKING_NUMBER)).willReturn(false);

    // when
    seatFacade.tryLockSeat(BOOKING_ID, BOOKING_NUMBER, SEAT_ID, USER_ID);

    // then: 락 해제 + 보상 이벤트 발행
    verify(seatUnlockUseCase).execute(SEAT_ID);
    verify(eventPublisher).publish(any(SeatHoldFailedEvent.class));
  }

  @Test
  @DisplayName("락 획득 실패(이미 선점): HOLD 시도 없이 보상 이벤트를 발행한다")
  void tryLockSeat_lockFail_publishesCompensation() {
    // given
    given(seatLockUseCase.execute(SEAT_ID, USER_ID)).willReturn(Optional.empty());

    // when
    seatFacade.tryLockSeat(BOOKING_ID, BOOKING_NUMBER, SEAT_ID, USER_ID);

    // then: HOLD·락 해제 없이 보상 이벤트만 발행
    verify(seatHoldUseCase, never()).execute(anyLong(), any(), anyString());
    verify(seatUnlockUseCase, never()).execute(anyLong());
    verify(eventPublisher).publish(any(SeatHoldFailedEvent.class));
  }

  @Test
  @DisplayName("락 획득 + HOLD 일시 오류: 락을 해제하고 예외를 전파하며 보상은 발행하지 않는다(재소비 위임)")
  void tryLockSeat_holdTransientError_rethrows() {
    // given: 일시 DB 오류(RuntimeException) → 트랜잭션 rollback-only, 같은 tx에서 보상 발행 불가
    LocalDateTime holdExpiredAt = LocalDateTime.now().plusMinutes(5);
    given(seatLockUseCase.execute(SEAT_ID, USER_ID)).willReturn(Optional.of(holdExpiredAt));
    given(seatHoldUseCase.execute(SEAT_ID, holdExpiredAt, BOOKING_NUMBER))
        .willThrow(new RuntimeException("DB 일시 장애"));

    // when & then
    assertThatThrownBy(() -> seatFacade.tryLockSeat(BOOKING_ID, BOOKING_NUMBER, SEAT_ID, USER_ID))
        .isInstanceOf(RuntimeException.class);

    // then: 락은 해제하되 보상 이벤트는 발행하지 않는다(재소비로 복구)
    verify(seatUnlockUseCase).execute(SEAT_ID);
    verify(eventPublisher, never()).publish(any());
  }

  @Test
  @DisplayName("발행 대상 보상 이벤트에 bookingId·seatId가 담긴다")
  void tryLockSeat_compensationCarriesIds() {
    // given
    given(seatLockUseCase.execute(SEAT_ID, USER_ID)).willReturn(Optional.empty());

    // when
    seatFacade.tryLockSeat(BOOKING_ID, BOOKING_NUMBER, SEAT_ID, USER_ID);

    // then
    verify(eventPublisher)
        .publish(
            argThat(
                e ->
                    e instanceof SeatHoldFailedEvent failed
                        && failed.bookingId().equals(BOOKING_ID)
                        && failed.seatId().equals(SEAT_ID)));
  }
}
