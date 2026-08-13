package com.ticketrush.boundedcontext.banner.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.banner.app.dto.response.BannerResponse;
import com.ticketrush.boundedcontext.banner.domain.entity.Banner;
import com.ticketrush.boundedcontext.banner.out.repository.BannerRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.util.S3UploadUtils;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배너 목록 조회 (#564).
 *
 * <p>정렬과 필터가 전부 쿼리의 산물이라 Mockito 단위 테스트로는 아무것도 검증되지 않는다. 실제 DB(H2)로 돌린다 — {@code display_order}가
 * 예약어를 피했는지도 여기서 드러난다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(
    exclude = {
      io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration.class,
      io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration.class
    })
@Transactional
class BannerGetListUseCaseTest {

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  @Autowired private BannerGetListUseCase bannerGetListUseCase;
  @Autowired private BannerRepository bannerRepository;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName("노출 순서 오름차순으로 반환한다 — 저장 순서와 무관하다")
  void execute_sortsByDisplayOrder() {
    bannerRepository.saveAll(List.of(banner("셋째", 3), banner("첫째", 1), banner("둘째", 2)));

    List<BannerResponse> result = bannerGetListUseCase.execute();

    assertThat(result).extracting(BannerResponse::title).containsExactly("첫째", "둘째", "셋째");
    assertThat(result).extracting(BannerResponse::order).containsExactly(1, 2, 3);
  }

  @Test
  @DisplayName("노출 순서가 같으면 배너 ID 오름차순으로 갈린다")
  void execute_tieBreaksById() {
    /*
     * 순서는 사람이 수동 UPDATE로 넣는 값이라 중복이 들어올 수 있다. tie-break가 없으면
     * 이 둘의 순서가 실행 계획에 따라 흔들려 캐러셀 순서가 배포마다 달라진다.
     *
     * 이 테스트가 잡는 것과 못 잡는 것을 분명히 해 둔다. 정렬을 IdDesc로 뒤집으면 실패하지만,
     * IdAsc를 통째로 지우면 통과한다(실측 확인). IDENTITY 채번이라 삽입 순서와 id 오름차순이
     * 같고, H2가 정렬 없이 PK 인덱스 순으로 돌려주는 순서까지 그와 일치하기 때문이다.
     * 즉 "정렬 방향"은 고정하지만 "정렬 키의 존재"는 고정하지 못한다. 그것까지 잡으려면
     * 생성 SQL의 ORDER BY 절을 단언해야 하는데, 배너 하나 때문에 그 하네스를 들이는 것은
     * 과하다고 판단했다. 리포지토리 메서드명을 고칠 때는 이 한계를 감안해야 한다.
     */
    Banner first = bannerRepository.save(banner("먼저 저장", 1));
    Banner second = bannerRepository.save(banner("나중 저장", 1));

    List<BannerResponse> result = bannerGetListUseCase.execute();

    assertThat(result)
        .extracting(BannerResponse::id)
        .containsExactly(first.getId(), second.getId());
  }

  @Test
  @DisplayName("비활성 배너는 목록에서 빠진다")
  void execute_excludesDeactivated() {
    Banner visible = bannerRepository.save(banner("노출", 1));
    Banner hidden = bannerRepository.save(banner("숨김", 2));
    deactivate(hidden);

    List<BannerResponse> result = bannerGetListUseCase.execute();

    assertThat(result).extracting(BannerResponse::id).containsExactly(visible.getId());
  }

  @Test
  @DisplayName("배너가 하나도 없으면 빈 목록이다")
  void execute_emptyWhenNoBanner() {
    assertThat(bannerGetListUseCase.execute()).isEmpty();
  }

  @Test
  @DisplayName("모든 배너가 비활성이면 빈 목록이다")
  void execute_emptyWhenAllDeactivated() {
    // 프론트는 빈 목록이면 배너 영역 자체를 렌더하지 않는다. 예외가 아니라 빈 목록이어야 한다.
    Banner only = bannerRepository.save(banner("숨김", 1));
    deactivate(only);

    assertThat(bannerGetListUseCase.execute()).isEmpty();
  }

  @Test
  @DisplayName("옵셔널 필드가 비어도 조회된다")
  void execute_allowsNullOptionalFields() {
    bannerRepository.save(Banner.builder().title("제목만 있는 배너").displayOrder(1).build());

    List<BannerResponse> result = bannerGetListUseCase.execute();

    assertThat(result).hasSize(1);
    BannerResponse response = result.getFirst();
    assertThat(response.title()).isEqualTo("제목만 있는 배너");
    assertThat(response.subtitle()).isNull();
    assertThat(response.date()).isNull();
    assertThat(response.linkConcertId()).isNull();
  }

  @Test
  @DisplayName("엔티티와 이름이 갈리는 필드가 응답에 제대로 실린다")
  void execute_mapsRenamedFields() {
    bannerRepository.save(
        Banner.builder()
            .title("Summer Jazz Night")
            .tagLabel("조기 예매 할인")
            .iconEmoji("🎵")
            .displayDate(LocalDate.of(2026, 9, 15))
            .linkPerformanceId(42L)
            .displayOrder(7)
            .build());

    BannerResponse response = bannerGetListUseCase.execute().getFirst();

    assertThat(response.date()).isEqualTo(LocalDate.of(2026, 9, 15));
    assertThat(response.linkConcertId()).isEqualTo(42L);
    assertThat(response.order()).isEqualTo(7);
    assertThat(response.tagLabel()).isEqualTo("조기 예매 할인");
    // 이모지가 저장·조회를 왕복해도 깨지지 않아야 한다
    assertThat(response.iconEmoji()).isEqualTo("🎵");
  }

  /** 운영에서 배너를 내리는 방법과 같다 — 관리자 API가 없어 UPDATE가 유일한 수단이다. */
  private void deactivate(Banner banner) {
    entityManager
        .createQuery("UPDATE Banner b SET b.deactivatedAt = :now WHERE b.id = :id")
        .setParameter("now", LocalDateTime.now())
        .setParameter("id", banner.getId())
        .executeUpdate();
    entityManager.flush();
    entityManager.clear();
  }

  private Banner banner(String title, int displayOrder) {
    return Banner.builder().title(title).displayOrder(displayOrder).build();
  }
}
