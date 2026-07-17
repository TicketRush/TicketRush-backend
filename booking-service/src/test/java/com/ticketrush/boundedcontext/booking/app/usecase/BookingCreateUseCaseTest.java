package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.booking.app.dto.request.BookingCreateRequest;
import com.ticketrush.boundedcontext.booking.app.mapper.BookingMapper;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.shared.booking.event.BookingCreatedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BookingCreateUseCaseTest {

  private BookingCreateUseCase bookingCreateUseCase;

  @Mock private BookingRepository bookingRepository;
  @Mock private BookingMapper bookingMapper;
  @Mock private EventPublisher eventPublisher;

  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    bookingCreateUseCase =
        new BookingCreateUseCase(bookingRepository, bookingMapper, eventPublisher, meterRegistry);
  }

  @Test
  @DisplayName("성공: 예약 요청을 받아 저장하고 내부 이벤트를 발행한다")
  void execute_success() {
    // given
    BookingCreateRequest request = new BookingCreateRequest(1L, 2L, 3L, "BOOK-1234");

    // mock() 대신 실제 객체 생성 (매퍼가 반환할 객체)
    Booking mappedBooking =
        Booking.builder()
            .userId(request.userId())
            .performanceId(request.performanceId())
            .seatId(request.seatId())
            .bookingNumber(request.bookingNumber())
            .bookingStatus(BookingStatus.PENDING)
            .build();

    // 저장 후 반환될 객체 (id가 부여된 상태를 가정)
    Booking savedBooking =
        Booking.builder()
            .userId(request.userId())
            .performanceId(request.performanceId())
            .seatId(request.seatId())
            .bookingNumber(request.bookingNumber())
            .bookingStatus(BookingStatus.PENDING)
            .build();
    ReflectionTestUtils.setField(savedBooking, "id", 100L); // AutoIdBaseEntity의 ID 강제 주입

    given(bookingMapper.toEntity(request)).willReturn(mappedBooking);
    given(bookingRepository.save(mappedBooking)).willReturn(savedBooking);

    // when
    Booking result = bookingCreateUseCase.execute(request);

    // then
    // 1. 반환값 검증
    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(100L);

    // 2. 저장(save) 로직이 정상 호출되었는지 검증
    verify(bookingRepository).save(mappedBooking);

    // 3. 도메인 이벤트가 트랜잭션 내부에서 정상적으로 발행되었는지 검증
    verify(eventPublisher).publish(any(BookingCreatedEvent.class));

    // 4. 예약 생성 성공 카운터가 1 증가했는지 검증
    assertThat(meterRegistry.counter(MetricNames.BOOKING_CREATED).count()).isEqualTo(1.0);
  }
}
