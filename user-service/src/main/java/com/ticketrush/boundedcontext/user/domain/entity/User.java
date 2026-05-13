package com.ticketrush.boundedcontext.user.domain.entity;

import com.ticketrush.boundedcontext.user.domain.types.UserRole;
import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "user")
public class User extends AutoIdBaseEntity {

  @Column(nullable = true, length = 50)
  private String name;

  @Column(nullable = true, length = 100)
  private String email;

  @Enumerated(EnumType.STRING)
  @Column(name = "user_role", nullable = false)
  private UserRole userRole;

  @Builder
  public User(String name, String email, UserRole userRole) {
    this.name = name;
    this.email = email;
    this.userRole = userRole;
  }
}
