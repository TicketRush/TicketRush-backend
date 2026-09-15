package com.ticketrush.boundedcontext.performance.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.util.S3UploadUtils;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
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
class PerformanceCharacterConfigH2RoundTripTest {

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  @Autowired private PerformanceRepository performanceRepository;
  @Autowired private EntityManager em;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String CONFIG =
      "{\"schemaVersion\":1,\"outfitModelId\":\"festival\","
          + "\"nested\":{\"hairColor\":\"#151515\"},\"list\":[1,2]}";

  /*
   * 레포 최초 JSON 컬럼(#650)의 왕복을 고정한다. 값 동일성(트리 비교)까지만 검증하는 이유: MySQL 8은 JSON을 바이너리로 정규화해
   * 키를 알파벳순으로 재정렬하고 공백을 넣어 돌려준다(로컬 MySQL 8.0 실측 — {"list": [1, 2], "nested": {...}, ...}).
   * H2(MySQL 모드)는 문자열을 그대로 돌려준다. 그래서 "보낸 JSON이 그대로"는 키 순서·공백이 아니라 값 동일성이 계약이다.
   */
  @Test
  @DisplayName("JSON 컬럼에 저장한 캐릭터 구성이 재조회 시 값이 같은 JSON으로 돌아온다 (키 케이스·중첩·배열 유지)")
  void roundTrip() {
    Performance saved =
        performanceRepository.save(
            Performance.builder()
                .title("t")
                .performer("p")
                .genre(Genre.CONCERT)
                .showDate(LocalDate.now().plusDays(1))
                .showTime(LocalTime.of(19, 0))
                .durationMinutes(120)
                .price(1000L)
                .totalSeats(10)
                .characterConfig(CONFIG)
                .characterMessage("안녕 🎵")
                .build());
    em.flush();
    em.clear();

    Performance found = performanceRepository.findById(saved.getId()).orElseThrow();
    assertThat(JSON.readTree(found.getCharacterConfig())).isEqualTo(JSON.readTree(CONFIG));
    assertThat(found.getCharacterMessage()).isEqualTo("안녕 🎵");
  }
}
