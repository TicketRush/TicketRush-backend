package com.ticketrush.global.jpa.entity;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.domain.Persistable;

@Getter
@MappedSuperclass
public abstract class ManualIdBaseEntity extends BaseTimeEntity implements Persistable<Long> {

  @Id private Long id;

  protected void assignId(Long id) {
    this.id = id;
  }

  // JPA에게 새로운 엔티티인지 판단하는 기준을 제공
  @Override
  public boolean isNew() {
    return getCreatedAt() == null;
  }
}
