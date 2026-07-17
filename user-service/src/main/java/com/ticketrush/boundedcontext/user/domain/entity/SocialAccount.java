package com.ticketrush.boundedcontext.user.domain.entity;

import com.ticketrush.boundedcontext.user.domain.types.SocialProvider;
import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "social_account",
    uniqueConstraints = {
      // 같은 provider + provider_user_id 조합은 단 하나만 존재 가능
      @UniqueConstraint(
          name = "uk_social_provider_provider_user_id",
          columnNames = {"social_provider", "provider_user_id"})
    })
public class SocialAccount extends AutoIdBaseEntity {

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false, unique = true)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(name = "social_provider", nullable = false)
  private SocialProvider socialProvider;

  @Column(name = "provider_user_id", nullable = false, length = 100)
  private String providerUserId;

  @Builder
  public SocialAccount(User user, SocialProvider socialProvider, String providerUserId) {
    this.user = user;
    this.socialProvider = socialProvider;
    this.providerUserId = providerUserId; // 소셜로그인이 준 고유 ID
  }

  public void setUser(User user) {
    this.user = user;
  }
}
