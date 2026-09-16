package com.ticketrush.boundedcontext.performance.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link Performance#updateCharacter}의 계약(#650)을 Spring 없이 고정한다. */
class PerformanceCharacterTest {

  private static final String CONFIG = "{\"schemaVersion\":1,\"outfitModelId\":\"festival\"}";

  @Test
  @DisplayName("updateCharacter: 한마디는 빈 문자열이면 삭제, null이면 유지하고 구성은 null이면 유지한다")
  void updateCharacter_contract() {
    Performance performance =
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
            .characterMessage("원래 한마디")
            .build();

    performance.updateCharacter(null, null);
    assertThat(performance.getCharacterConfig()).isEqualTo(CONFIG);
    assertThat(performance.getCharacterMessage()).isEqualTo("원래 한마디");

    performance.updateCharacter(null, "");
    assertThat(performance.getCharacterConfig()).isEqualTo(CONFIG);
    assertThat(performance.getCharacterMessage()).isNull();

    performance.updateCharacter("{\"schemaVersion\":2}", "새 한마디");
    assertThat(performance.getCharacterConfig()).isEqualTo("{\"schemaVersion\":2}");
    assertThat(performance.getCharacterMessage()).isEqualTo("새 한마디");
  }

  @Test
  @DisplayName("updateCharacter: 공백만 있는 한마디도 삭제로 본다")
  void updateCharacter_blankMessage_deletes() {
    Performance performance =
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
            .characterMessage("원래 한마디")
            .build();

    performance.updateCharacter(null, "   ");

    assertThat(performance.getCharacterMessage()).isNull();
  }
}
