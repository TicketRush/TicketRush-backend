package com.ticketrush.boundedcontext.seat.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

/**
 * {@code Seat.version}의 {@code columnDefinition}이 실제로 DDL에 {@code DEFAULT 0}을 만드는지 검증한다(#433).
 *
 * <p>이 보장이 깨지면 {@code version}을 명시하지 않는 시딩 SQL(load-test/seed/seed_load.sql 등)이 {@code ERROR 1364}로
 * 깨진다. {@code schema-validate} CI의 {@code ddl-auto=validate}는 DEFAULT 차이를 검출하지 못하므로 여기서 잡는다.
 */
@DataJpaTest
class SeatVersionDefaultTest {

  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName("Hibernate가 생성한 seat.version 컬럼에 DEFAULT 0이 붙는다 (#433)")
  void versionColumn_HasDefaultZero() {
    Object columnDefault =
        entityManager
            .createNativeQuery(
                "SELECT column_default FROM information_schema.columns "
                    + "WHERE table_name = 'SEAT' AND column_name = 'VERSION'")
            .getSingleResult();

    assertThat(columnDefault).asString().contains("0");
  }
}
