package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.shared.booking.event.BookingExpiredEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class BookingExpireUseCaseTest {

  private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
  private static final Instant NOW = Instant.parse("2026-05-27T06:00:00Z");

  @InjectMocks private BookingExpireUseCase bookingExpireUseCase;

  @Mock private BookingRepository bookingRepository;

  @Mock private TransactionTemplate transactionTemplate;

  @Mock private EventPublisher eventPublisher;

  @Mock private Clock clock;

  @BeforeEach
  void setUp() {
    lenient()
        .when(transactionTemplate.execute(any()))
        .thenAnswer(invocation -> executeTransaction(invocation.getArgument(0)));
  }

  @Test
  @DisplayName("성공: 생성 후 5분이 지난 PENDING 예매를 EXPIRED로 전이한다")
  void execute_success() {
    // given
    List<Long> bookingIds = List.of(1L);
    given(clock.instant()).willReturn(NOW);
    given(clock.getZone()).willReturn(ZONE_ID);
    given(
            bookingRepository.findExpiredPendingBookingIds(
                eq(BookingStatus.PENDING),
                eq(LocalDateTime.of(2026, 5, 27, 14, 55)),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
        .willReturn(bookingIds);
    given(
            bookingRepository.expirePendingBookingById(
                1L, BookingStatus.PENDING, BookingStatus.EXPIRED))
        .willReturn(1);

    // when
    int result = bookingExpireUseCase.execute();

    // then
    assertThat(result).isEqualTo(1);
    verify(eventPublisher)
        .publish(new BookingExpiredEvent(1L, LocalDateTime.of(2026, 5, 27, 15, 0)));
  }

  @Test
  @DisplayName("성공: 현재 시각 기준 5분 전을 만료 조회 기준으로 사용한다")
  void execute_uses_cutoff_from_current_time() {
    // given
    given(clock.instant()).willReturn(NOW);
    given(clock.getZone()).willReturn(ZONE_ID);
    given(
            bookingRepository.findExpiredPendingBookingIds(
                eq(BookingStatus.PENDING),
                eq(LocalDateTime.of(2026, 5, 27, 14, 55)),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
        .willReturn(List.of());

    // when
    bookingExpireUseCase.execute();

    // then
    ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(bookingRepository)
        .findExpiredPendingBookingIds(
            eq(BookingStatus.PENDING),
            cutoffCaptor.capture(),
            org.mockito.ArgumentMatchers.any(Pageable.class));
    assertThat(cutoffCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 5, 27, 14, 55));
  }

  @Test
  @DisplayName("성공: 조회 결과가 비어 있으면 전이 없이 0을 반환한다")
  void execute_returns_zero_when_no_expired_pending_booking() {
    // given
    given(clock.instant()).willReturn(NOW);
    given(clock.getZone()).willReturn(ZONE_ID);
    given(
            bookingRepository.findExpiredPendingBookingIds(
                eq(BookingStatus.PENDING),
                eq(LocalDateTime.of(2026, 5, 27, 14, 55)),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
        .willReturn(List.of());

    // when
    int result = bookingExpireUseCase.execute();

    // then
    assertThat(result).isZero();
  }

  @Test
  @DisplayName("성공: 만료 대상이 100건 이상이면 같은 실행에서 다음 배치를 이어서 처리한다")
  void execute_processes_next_batch_when_first_batch_is_full() {
    // given
    List<Long> firstBatch = createBookingIds(1, 100);
    List<Long> secondBatch = createBookingIds(101, 103);
    given(clock.instant()).willReturn(NOW);
    given(clock.getZone()).willReturn(ZONE_ID);
    given(
            bookingRepository.findExpiredPendingBookingIds(
                eq(BookingStatus.PENDING),
                eq(LocalDateTime.of(2026, 5, 27, 14, 55)),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
        .willReturn(firstBatch, secondBatch);
    given(
            bookingRepository.expirePendingBookingById(
                org.mockito.ArgumentMatchers.longThat(id -> id >= 1 && id <= 100),
                eq(BookingStatus.PENDING),
                eq(BookingStatus.EXPIRED)))
        .willReturn(1);
    given(
            bookingRepository.expirePendingBookingById(
                org.mockito.ArgumentMatchers.longThat(id -> id >= 101 && id <= 103),
                eq(BookingStatus.PENDING),
                eq(BookingStatus.EXPIRED)))
        .willReturn(1);

    // when
    int result = bookingExpireUseCase.execute();

    // then
    assertThat(result).isEqualTo(103);
  }

  @Test
  @DisplayName("성공: 조회된 예매가 경합으로 이미 PENDING이 아니면 조건부 UPDATE 결과만 집계한다")
  void execute_counts_only_conditionally_updated_rows() {
    // given
    List<Long> bookingIds = List.of(1L, 2L, 3L);
    given(clock.instant()).willReturn(NOW);
    given(clock.getZone()).willReturn(ZONE_ID);
    given(
            bookingRepository.findExpiredPendingBookingIds(
                eq(BookingStatus.PENDING),
                eq(LocalDateTime.of(2026, 5, 27, 14, 55)),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
        .willReturn(bookingIds);
    given(
            bookingRepository.expirePendingBookingById(
                1L, BookingStatus.PENDING, BookingStatus.EXPIRED))
        .willReturn(1);
    given(
            bookingRepository.expirePendingBookingById(
                2L, BookingStatus.PENDING, BookingStatus.EXPIRED))
        .willReturn(0);
    given(
            bookingRepository.expirePendingBookingById(
                3L, BookingStatus.PENDING, BookingStatus.EXPIRED))
        .willReturn(1);

    // when
    int result = bookingExpireUseCase.execute();

    // then
    assertThat(result).isEqualTo(2);
    verify(eventPublisher)
        .publish(new BookingExpiredEvent(1L, LocalDateTime.of(2026, 5, 27, 15, 0)));
    verify(eventPublisher)
        .publish(new BookingExpiredEvent(3L, LocalDateTime.of(2026, 5, 27, 15, 0)));
    verify(eventPublisher, never())
        .publish(new BookingExpiredEvent(2L, LocalDateTime.of(2026, 5, 27, 15, 0)));
  }

  private List<Long> createBookingIds(long startInclusive, long endInclusive) {
    java.util.ArrayList<Long> bookingIds = new java.util.ArrayList<>();
    for (long id = startInclusive; id <= endInclusive; id++) {
      bookingIds.add(id);
    }
    return bookingIds;
  }

  private Integer executeTransaction(TransactionCallback<Integer> callback) {
    return callback.doInTransaction(null);
  }
}
