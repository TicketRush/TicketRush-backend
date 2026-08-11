package com.ticketrush.boundedcontext.payment.app.usecase;

import static java.util.Comparator.reverseOrder;
import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentSummaryResponse;
import com.ticketrush.boundedcontext.payment.app.mapper.PaymentMapper;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Page;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * {@link PaymentGetListUseCase}가 고정 조립하는 정렬을 실제 DB로 실행해 검증한다(#475).
 *
 * <p>{@code PaymentGetListUseCaseTest}는 리포지토리를 mock으로 두고 {@code Sort} 객체의 equals만 비교하므로, 정렬 프로퍼티명이
 * 엔티티에 실존하는지는 검증 범위 밖이다. 프로퍼티명이 틀리면 {@code PropertyReferenceException}으로 500이 나가는데 그 경로는 실제
 * EntityManager를 태워야만 드러난다. 특히 tie-break로 쓰는 {@code id}는 {@code AutoIdBaseEntity}에서 상속받고
 * {@code @AttributeOverride}로 컬럼명이 {@code payment_id}가 되므로 해석이 자명하지 않다.
 *
 * <p>Docker가 없는 환경에서는 컨테이너 기동 실패로 깨진다. {@code PaymentCompletedBookingUniqueTest}와 같은 fail-closed
 * 방침이다 — silent skip을 허용하면 이 그물이 CI에서 조용히 사라져도 아무도 모른다.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=update")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PaymentGetListUseCaseIntegrationTest {

  private static final Long USER_ID = 1L;

  /* prod(deploy/docker-compose.prod.yml)와 동일한 mysql:8.0 + utf8mb4_unicode_ci로 맞춘다. */
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer("mysql:8.0")
          .withDatabaseName("ticket_rush")
          .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
  }

  @Autowired private PaymentRepository paymentRepository;

  @Autowired private PaymentGetListUseCase paymentGetListUseCase;

  @Test
  @DisplayName("고정 정렬의 프로퍼티명이 실제 엔티티로 해석된다 — 예외 없이 조회된다")
  void execute_resolvesSortPropertiesAgainstEntity() {
    // given
    paymentRepository.saveAll(
        List.of(
            payment(100L, LocalDateTime.of(2026, 5, 1, 10, 0)),
            payment(101L, LocalDateTime.of(2026, 5, 2, 10, 0))));
    paymentRepository.flush();

    // when
    Page<PaymentSummaryResponse> result =
        paymentGetListUseCase.execute(USER_ID, new OffsetPageRequest(0, 10));

    // then — paidAt이 늦은 건이 먼저 온다
    assertThat(result.getTotalElements()).isEqualTo(2);
    assertThat(result.getContent())
        .extracting(PaymentSummaryResponse::bookingId)
        .containsExactly(101L, 100L);
  }

  @Test
  @DisplayName("paidAt이 모두 동일해도 페이지 경계에서 중복·누락이 없다 — id tie-break로 전순서가 확정된다")
  void execute_breaksTieByIdAcrossPageBoundary() {
    // given — paidAt이 완전히 동일한 4건. tie-break가 없으면 페이지 간 순서가 보장되지 않는다.
    LocalDateTime sameInstant = LocalDateTime.of(2026, 5, 1, 10, 0, 0);
    paymentRepository.saveAll(
        List.of(
            payment(100L, sameInstant),
            payment(101L, sameInstant),
            payment(102L, sameInstant),
            payment(103L, sameInstant)));
    paymentRepository.flush();

    // when — 경계를 실제로 넘도록 size 2로 두 페이지를 연속 조회한다
    List<Long> firstPage = paymentIdsOf(paymentGetListUseCase.execute(USER_ID, page(0, 2)));
    List<Long> secondPage = paymentIdsOf(paymentGetListUseCase.execute(USER_ID, page(1, 2)));

    // then — 두 페이지를 합치면 중복 0건, 누락 0건이고 전체가 id 내림차순이다
    assertThat(firstPage).hasSize(2);
    assertThat(secondPage).hasSize(2);
    assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);

    List<Long> merged = new ArrayList<>(firstPage);
    merged.addAll(secondPage);
    assertThat(merged).hasSize(4).doesNotHaveDuplicates().isSortedAccordingTo(reverseOrder());
  }

  private OffsetPageRequest page(int page, int size) {
    return new OffsetPageRequest(page, size);
  }

  private List<Long> paymentIdsOf(Page<PaymentSummaryResponse> page) {
    return page.getContent().stream().map(PaymentSummaryResponse::paymentId).toList();
  }

  private Payment payment(Long bookingId, LocalDateTime paidAt) {
    return Payment.builder()
        .bookingId(bookingId)
        .userId(USER_ID)
        .provider(PaymentProvider.TOSS)
        .amount(55_000L)
        .status(PaymentStatus.COMPLETED)
        .paymentKey("pgKey_" + bookingId)
        .approvalNumber("APR-" + bookingId)
        .paidAt(paidAt)
        .build();
  }

  /*
   * @DataJpaTest 슬라이스엔 @Service·MapStruct 빈이 없어 UseCase를 직접 올린다.
   * 테스트 클래스의 static 중첩 @TestConfiguration은 Boot가 자동 등록하므로 @Import가 필요 없다.
   */
  @TestConfiguration
  static class UseCaseTestConfig {

    @Bean
    PaymentMapper paymentMapper() {
      return Mappers.getMapper(PaymentMapper.class);
    }

    @Bean
    PaymentGetListUseCase paymentGetListUseCase(
        PaymentRepository paymentRepository, PaymentMapper paymentMapper) {
      return new PaymentGetListUseCase(paymentRepository, paymentMapper);
    }
  }
}
