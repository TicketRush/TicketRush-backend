package com.ticketrush.boundedcontext.performance.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Performance#replaceFiles}·{@link Performance#clearModel3d}의 계약(#637·#688)을 Spring 없이 고정한다.
 *
 * <p>DB 왕복까지 포함한 검증은 {@code PerformanceReplaceFilesTest}가 하고, 여기서는 "null 은 유지, 목록은 치환(빈 목록이면 비움),
 * 비우기는 별도 메서드"라는 도메인 규칙 자체만 본다.
 */
class PerformanceReplaceFilesEntityTest {

  private static final String MAIN = "https://s3.example/main.png";
  private static final String MODEL = "https://s3.example/model.glb";
  private static final String G1 = "https://s3.example/g1.png";
  private static final String G2 = "https://s3.example/g2.png";

  private static Performance performance() {
    return Performance.builder()
        .title("t")
        .performer("p")
        .genre(Genre.CONCERT)
        .showDate(LocalDate.now().plusDays(1))
        .showTime(LocalTime.of(19, 0))
        .durationMinutes(120)
        .price(1000L)
        .totalSeats(10)
        .imageMainUrl(MAIN)
        .image3dUrl(MODEL)
        .imageGalleryUrls(new ArrayList<>(List.of(G1, G2)))
        .build();
  }

  @Test
  @DisplayName("replaceFiles: null 인 파트는 전부 유지한다")
  void replaceFiles_nullKeeps() {
    Performance performance = performance();

    performance.replaceFiles(null, null, null);

    assertThat(performance.getImageMainUrl()).isEqualTo(MAIN);
    assertThat(performance.getImage3dUrl()).isEqualTo(MODEL);
    assertThat(performance.getImageGalleryUrls()).containsExactly(G1, G2);
  }

  @Test
  @DisplayName("replaceFiles: 갤러리 목록을 주면 그 목록으로 치환하고, 빈 목록이면 비운다 (#688부터 빈 목록 가드 없음)")
  void replaceFiles_listReplacesAndEmptyClears() {
    Performance performance = performance();
    final List<String> before = performance.getImageGalleryUrls();

    performance.replaceFiles(null, null, List.of("https://s3.example/new.png"));
    assertThat(performance.getImageGalleryUrls()).containsExactly("https://s3.example/new.png");

    performance.replaceFiles(null, null, List.of());
    assertThat(performance.getImageGalleryUrls()).isEmpty();

    // 컬렉션 인스턴스는 갈아끼우지 않는다 — 영속 상태에서 PersistentCollection 래퍼가 떨어져 나가지 않게 하는 계약
    assertThat(performance.getImageGalleryUrls()).isSameAs(before);
  }

  @Test
  @DisplayName("clearModel3d: 3D 모델 URL 만 null 이 되고, 이후 replaceFiles(null) 은 그 null 을 유지한다")
  void clearModel3d_onlyModelBecomesNull() {
    Performance performance = performance();

    performance.clearModel3d();

    assertThat(performance.getImage3dUrl()).isNull();
    assertThat(performance.getImageMainUrl()).isEqualTo(MAIN);
    assertThat(performance.getImageGalleryUrls()).containsExactly(G1, G2);

    performance.replaceFiles(null, null, null);
    assertThat(performance.getImage3dUrl()).isNull();

    performance.replaceFiles(null, "https://s3.example/again.glb", null);
    assertThat(performance.getImage3dUrl()).isEqualTo("https://s3.example/again.glb");
  }
}
