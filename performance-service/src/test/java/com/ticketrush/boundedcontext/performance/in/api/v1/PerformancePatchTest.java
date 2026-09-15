package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformancePatchRequest;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformancePatchUseCase;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.util.S3UploadUtils;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(
    exclude = {
      io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration.class,
      io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration.class
    })
@Transactional
class PerformancePatchTest {

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  @Autowired private PerformancePatchUseCase performancePatchUseCase;
  @Autowired private PerformanceRepository performanceRepository;
  @Autowired private EntityManager em;

  private static final LocalDateTime ORIGINAL_BOOKING_OPEN_AT = LocalDateTime.of(2025, 8, 1, 20, 0);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ORIGINAL_CONFIG =
      "{\"schemaVersion\":1,\"outfitModelId\":\"festival\"}";
  private static final String ORIGINAL_MESSAGE = "원래 한마디";

  private PerformancePatchRequest characterOnly(String configJson, String message) {
    return new PerformancePatchRequest(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        configJson == null ? null : JSON.readTree(configJson),
        message);
  }

  private Performance savePerformance() {
    return performanceRepository.save(
        Performance.builder()
            .characterConfig(ORIGINAL_CONFIG)
            .characterMessage(ORIGINAL_MESSAGE)
            .title("원래 공연명")
            .performer("원래 출연진")
            .genre(Genre.CONCERT)
            .description("원래 설명")
            .showDate(LocalDate.now().plusDays(30))
            .showTime(LocalTime.of(19, 0))
            .durationMinutes(120)
            .price(50000L)
            .totalSeats(100)
            .address("서울")
            .bookingOpenAt(ORIGINAL_BOOKING_OPEN_AT)
            .build());
  }

  @Test
  @DisplayName("전체 필드를 수정하면 DB에 반영된다 (총 좌석 수는 수정 대상이 아니다)")
  void patchAllFields_success() {
    Performance performance = savePerformance();
    LocalDate newShowDate = LocalDate.now().plusDays(60);
    LocalTime newShowTime = LocalTime.of(20, 0);
    LocalDateTime newBookingOpenAt = LocalDateTime.of(2025, 9, 1, 20, 0);

    performancePatchUseCase.execute(
        performance.getId(),
        new PerformancePatchRequest(
            "새로운 공연명",
            "새로운 출연진",
            Genre.MUSICAL,
            "새로운 설명",
            newShowDate,
            newShowTime,
            150,
            80000L,
            "부산",
            newBookingOpenAt,
            null,
            null));

    em.flush();
    em.clear();

    Performance updated = performanceRepository.findById(performance.getId()).orElseThrow();
    assertThat(updated.getTitle()).isEqualTo("새로운 공연명");
    assertThat(updated.getPerformer()).isEqualTo("새로운 출연진");
    assertThat(updated.getGenre()).isEqualTo(Genre.MUSICAL);
    assertThat(updated.getDescription()).isEqualTo("새로운 설명");
    assertThat(updated.getShowDate()).isEqualTo(newShowDate);
    assertThat(updated.getShowTime()).isEqualTo(newShowTime);
    assertThat(updated.getDurationMinutes()).isEqualTo(150);
    assertThat(updated.getPrice()).isEqualTo(80000L);
    // 총 좌석 수는 PATCH 대상이 아니다(#590). 좌석 수의 원본은 좌석 서비스이고, 여기서 고쳐도 seat으로 나가지
    // 않아(PerformancePatchUseCase에 EventPublisher가 없다) 두 값이 조용히 갈렸다. 등록 시점에만 정한다.
    assertThat(updated.getTotalSeats()).isEqualTo(100);
    assertThat(updated.getAddress()).isEqualTo("부산");
    assertThat(updated.getBookingOpenAt()).isEqualTo(newBookingOpenAt);
  }

  @Test
  @DisplayName("null 필드는 수정하지 않고 기존 값을 유지한다")
  void patchPartialFields_nullFieldsUnchanged() {
    Performance performance = savePerformance();

    performancePatchUseCase.execute(
        performance.getId(),
        new PerformancePatchRequest(
            "새로운 공연명", null, null, null, null, null, null, null, null, null, null, null));

    em.flush();
    em.clear();

    Performance updated = performanceRepository.findById(performance.getId()).orElseThrow();
    assertThat(updated.getTitle()).isEqualTo("새로운 공연명");
    assertThat(updated.getPerformer()).isEqualTo("원래 출연진");
    assertThat(updated.getGenre()).isEqualTo(Genre.CONCERT);
    assertThat(updated.getDescription()).isEqualTo("원래 설명");
    assertThat(updated.getPrice()).isEqualTo(50000L);
    assertThat(updated.getBookingOpenAt()).isEqualTo(ORIGINAL_BOOKING_OPEN_AT);
  }

  @Test
  @DisplayName("존재하지 않는 공연 ID로 수정 요청 시 예외 발생")
  void patchPerformance_notFound() {
    PerformancePatchRequest request =
        new PerformancePatchRequest(
            "새 제목", null, null, null, null, null, null, null, null, null, null, null);

    assertThatThrownBy(() -> performancePatchUseCase.execute(999L, request))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorStatus.PERFORMANCE_NOT_FOUND.getMessage());
  }

  /*
   * #650 — 캐릭터 필드의 PATCH 계약. characterMessage만 "빈 문자열=삭제"라는 규칙이 추가됐다(이 레포 PATCH 최초).
   */
  @Test
  @DisplayName("characterMessage에 빈 문자열을 보내면 한마디가 삭제(null)되고 구성은 유지된다")
  void patchCharacterMessage_emptyString_deletes() {
    Performance performance = savePerformance();

    performancePatchUseCase.execute(performance.getId(), characterOnly(null, ""));
    em.flush();
    em.clear();

    Performance updated = performanceRepository.findById(performance.getId()).orElseThrow();
    assertThat(updated.getCharacterMessage()).isNull();
    assertThat(JSON.readTree(updated.getCharacterConfig()))
        .isEqualTo(JSON.readTree(ORIGINAL_CONFIG));
  }

  @Test
  @DisplayName("characterMessage가 공백만 있는 문자열이어도 삭제로 본다 (등록의 정규화와 같은 기준)")
  void patchCharacterMessage_blank_deletes() {
    Performance performance = savePerformance();

    performancePatchUseCase.execute(performance.getId(), characterOnly(null, "   "));
    em.flush();
    em.clear();

    assertThat(
            performanceRepository.findById(performance.getId()).orElseThrow().getCharacterMessage())
        .isNull();
  }

  @Test
  @DisplayName("캐릭터 필드가 null이면 구성·한마디 모두 기존 값을 유지한다")
  void patchCharacter_null_unchanged() {
    Performance performance = savePerformance();

    performancePatchUseCase.execute(performance.getId(), characterOnly(null, null));
    em.flush();
    em.clear();

    Performance updated = performanceRepository.findById(performance.getId()).orElseThrow();
    assertThat(updated.getCharacterMessage()).isEqualTo(ORIGINAL_MESSAGE);
    assertThat(JSON.readTree(updated.getCharacterConfig()))
        .isEqualTo(JSON.readTree(ORIGINAL_CONFIG));
  }

  @Test
  @DisplayName("characterConfig에 객체를 보내면 통째로 덮어쓰고 (빈 객체 포함) 한마디는 새 값으로 바뀐다")
  void patchCharacterConfig_object_overwrites() {
    Performance performance = savePerformance();
    String newConfig = "{\"schemaVersion\":2,\"nested\":{\"hairColor\":\"#fff\"}}";

    performancePatchUseCase.execute(performance.getId(), characterOnly(newConfig, "새 한마디"));
    em.flush();
    em.clear();

    Performance updated = performanceRepository.findById(performance.getId()).orElseThrow();
    assertThat(JSON.readTree(updated.getCharacterConfig())).isEqualTo(JSON.readTree(newConfig));
    assertThat(updated.getCharacterMessage()).isEqualTo("새 한마디");

    performancePatchUseCase.execute(performance.getId(), characterOnly("{}", null));
    em.flush();
    em.clear();

    Performance emptied = performanceRepository.findById(performance.getId()).orElseThrow();
    assertThat(JSON.readTree(emptied.getCharacterConfig())).isEqualTo(JSON.readTree("{}"));
    assertThat(emptied.getCharacterMessage()).isEqualTo("새 한마디");
  }
}
