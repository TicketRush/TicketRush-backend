package com.ticketrush.boundedcontext.performance.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformancePatchRequest;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 레포 최초 JSON 컬럼({@code character_config}, #650)의 INSERT → UPDATE(PATCH 경로) → SELECT 왕복을 <b>실제 MySQL
 * 8</b>로 검증한다.
 *
 * <p>H2(MySQL 모드) 테스트만으로는 부족하다 — MySQL은 JSON을 바이너리로 정규화해 키를 알파벳순으로 재정렬하고 공백을 넣어 돌려주며, UPDATE
 * 바인딩({@code cast(? as json)})은 H2와 다른 JdbcType을 탄다. 그래서 값 동일성(트리 비교)으로 검증한다. 문자열 동일성은 MySQL에서 성립하지
 * 않는다.
 *
 * <p>Docker가 없는 환경에서는 컨테이너 기동 실패로 깨진다. {@code PerformanceListCacheTest}와 같은 fail-closed 방침이다.
 *
 * <p>패키지가 {@code app.usecase}인 이유: 테스트 클래스의 {@code @EnableAutoConfiguration}은 자기 패키지를 자동 설정 기준
 * 패키지로도 등록한다. 리포지토리 인터페이스가 있는 {@code out.repository}에 두면 같은 리포지토리가 두 기준 패키지에서 스캔돼 빈 정의 충돌로 컨텍스트가 뜨지
 * 않는다(실측).
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(
    exclude = {
      io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration.class,
      io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration.class
    })
@Testcontainers
@Transactional
class PerformanceCharacterConfigMySqlRoundTripTest {

  /* prod(deploy/docker-compose.prod.yml)와 동일한 mysql:8.0 + utf8mb4_unicode_ci (payment #475 선례). */
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
    // test 프로파일은 H2Dialect를 명시하고 있어 MySQL로 덮어야 json JdbcType이 MySQL 것으로 잡힌다.
    registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
  }

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  @Autowired private PerformanceRepository performanceRepository;
  @Autowired private PerformancePatchUseCase performancePatchUseCase;
  @Autowired private EntityManager em;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String INITIAL =
      "{\"schemaVersion\":1,\"outfitModelId\":\"festival\","
          + "\"nested\":{\"hairColor\":\"#151515\"},\"list\":[1,2],\"accessory\":null}";
  private static final String UPDATED =
      "{\"schemaVersion\":2,\"outfitModelId\":\"casual\",\"list\":[]}";

  @Test
  @DisplayName("MySQL 8 json 컬럼: INSERT 후 PATCH(UPDATE)한 캐릭터 구성이 값이 같은 JSON으로 돌아온다")
  void insertThenUpdate_roundTripOnMySql() {
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
                .characterConfig(INITIAL)
                .characterMessage("안녕 🎵")
                .build());
    em.flush();
    em.clear();

    Performance inserted = performanceRepository.findById(saved.getId()).orElseThrow();
    assertThat(JSON.readTree(inserted.getCharacterConfig())).isEqualTo(JSON.readTree(INITIAL));
    assertThat(inserted.getCharacterMessage()).isEqualTo("안녕 🎵");

    performancePatchUseCase.execute(
        saved.getId(),
        new PerformancePatchRequest(
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
            JSON.readTree(UPDATED),
            "",
            null,
            null));

    em.flush();
    em.clear();

    Performance updated = performanceRepository.findById(saved.getId()).orElseThrow();
    assertThat(JSON.readTree(updated.getCharacterConfig())).isEqualTo(JSON.readTree(UPDATED));
    assertThat(updated.getCharacterMessage()).isNull();

    Object raw =
        em.createNativeQuery("select character_config from performance where performance_id = ?")
            .setParameter(1, saved.getId())
            .getSingleResult();

    assertThat(JSON.readTree(raw.toString())).isEqualTo(JSON.readTree(UPDATED));
  }
}
