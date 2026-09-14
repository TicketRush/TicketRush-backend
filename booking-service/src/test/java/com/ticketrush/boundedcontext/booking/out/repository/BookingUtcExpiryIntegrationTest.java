package com.ticketrush.boundedcontext.booking.out.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingExpireUseCase;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.jpa.config.JpaConfig;
import com.ticketrush.shared.booking.event.BookingExpiredEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
@Import({JpaConfig.class, BookingUtcExpiryIntegrationTest.FixedTimeConfig.class})
@ResourceLock("java.util.TimeZone.default")
class BookingUtcExpiryIntegrationTest {

  private static final Instant CREATED = Instant.parse("2026-12-31T23:58:00.123456Z");

  @Autowired private BookingRepository repository;
  @Autowired private TestEntityManager em;
  @Autowired private PlatformTransactionManager transactionManager;

  @TestConfiguration
  static class FixedTimeConfig {
    @Bean
    Clock clock() {
      return Clock.fixed(CREATED, ZoneOffset.UTC);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTC", "Asia/Seoul"})
  void persistedAuditingControlsWireExpiryAndExactDueBoundary(String zone) {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      assertThat(TimeZone.getDefault().getID()).isEqualTo(zone);
      Booking saved =
          repository.saveAndFlush(
              Booking.builder()
                  .bookingNumber("UTC-646")
                  .userId(1L)
                  .performanceId(2L)
                  .seatId(3L)
                  .bookingStatus(BookingStatus.PENDING)
                  .build());
      Long id = saved.getId();
      em.clear();
      Booking persisted = repository.findById(id).orElseThrow();
      LocalDateTime createdAt = LocalDateTime.ofInstant(CREATED, ZoneOffset.UTC);
      assertThat(persisted.getCreatedAt()).isEqualTo(createdAt);
      assertThat(persisted.getUpdatedAt()).isEqualTo(createdAt);
      LocalDateTime expiresAt = createdAt.plusMinutes(Booking.PAYMENT_WAIT_MINUTES);
      assertThat(Booking.PAYMENT_WAIT_MINUTES).isEqualTo(5);
      assertThat(BookingSummaryResponse.from(persisted).expiresAt()).isEqualTo(expiresAt);

      JsonMapper.Builder builder = JsonMapper.builder();
      new JacksonConfig().jacksonCustomizer().customize(builder);
      JsonMapper mapper = builder.build();
      String wire =
          mapper
              .readTree(mapper.writeValueAsString(BookingSummaryResponse.from(persisted)))
              .get("expires_at")
              .asText();
      assertThat(Instant.parse(wire))
          .isEqualTo(expiresAt.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));

      EventPublisher publisher = mock(EventPublisher.class);
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);
      Instant boundary = expiresAt.toInstant(ZoneOffset.UTC);
      BookingExpireUseCase before =
          new BookingExpireUseCase(
              repository,
              transaction,
              publisher,
              Clock.fixed(boundary.minus(1, ChronoUnit.MICROS), ZoneOffset.UTC));
      assertThat(before.execute()).isZero();
      verify(publisher, never()).publish(any());
      assertThat(repository.findById(id).orElseThrow().getBookingStatus())
          .isEqualTo(BookingStatus.PENDING);

      BookingExpireUseCase due =
          new BookingExpireUseCase(
              repository, transaction, publisher, Clock.fixed(boundary, ZoneOffset.UTC));
      assertThat(due.execute()).isEqualTo(1);
      em.clear();
      assertThat(repository.findById(id).orElseThrow().getBookingStatus())
          .isEqualTo(BookingStatus.EXPIRED);
      ArgumentCaptor<BookingExpiredEvent> event =
          ArgumentCaptor.forClass(BookingExpiredEvent.class);
      verify(publisher).publish(event.capture());
      assertThat(event.getValue().bookingId()).isEqualTo(id);
      assertThat(event.getValue().expiredAt()).isEqualTo(expiresAt);
      assertThat(due.execute()).isZero();
    } finally {
      TimeZone.setDefault(original);
    }
  }
}
