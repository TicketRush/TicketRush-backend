package com.ticketrush.boundedcontext.user.out.repository;

import com.ticketrush.boundedcontext.user.domain.entity.SocialAccount;
import com.ticketrush.boundedcontext.user.domain.types.SocialProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

  Optional<SocialAccount> findByProviderUserIdAndSocialProvider(
      String providerUserId, SocialProvider socialProvider);
}
