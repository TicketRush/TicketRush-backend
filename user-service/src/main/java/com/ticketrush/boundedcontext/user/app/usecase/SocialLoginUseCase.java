package com.ticketrush.boundedcontext.user.app.usecase;

import com.ticketrush.boundedcontext.user.app.dto.request.SocialLoginRequest;
import com.ticketrush.boundedcontext.user.app.dto.response.SocialLoginResponse;
import com.ticketrush.boundedcontext.user.app.mapper.SocialMapper;
import com.ticketrush.boundedcontext.user.domain.entity.SocialAccount;
import com.ticketrush.boundedcontext.user.domain.entity.User;
import com.ticketrush.boundedcontext.user.domain.types.SocialProvider;
import com.ticketrush.boundedcontext.user.out.repository.SocialAccountRepository;
import com.ticketrush.boundedcontext.user.out.repository.UserRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SocialLoginUseCase {

  private final SocialAccountRepository socialAccountRepository;
  private final UserRepository userRepository;
  private final SocialMapper socialMapper;

  @Transactional
  public SocialLoginResponse execute(SocialLoginRequest request) {

    log.info("🔥 user-service 받은 request = {}", request);

    validate(request);

    // 🔥 enum 변환 (핵심)
    SocialProvider provider = parseProvider(request.getSocialProvider());

    // 1. 소셜 계정 조회
    Optional<SocialAccount> account =
        socialAccountRepository.findByProviderUserIdAndSocialProvider(
            request.getSocialId(), provider);

    // 2. 기존 유저 → 로그인
    if (account.isPresent()) {
      User user = account.get().getUser();
      return new SocialLoginResponse(user.getId(), user.getName(), false);
    }

    // 3. 신규 유저 생성
    User newUser = socialMapper.toUser(request);
    userRepository.save(newUser);

    // 4. 소셜 계정 생성
    SocialAccount socialAccount = socialMapper.toSocialAccount(request, provider);
    socialAccount.setUser(newUser);
    socialAccountRepository.save(socialAccount);

    return new SocialLoginResponse(newUser.getId(), newUser.getName(), true);
  }

  // 입력값 검증
  private void validate(SocialLoginRequest request) {
    if (request.getSocialProvider() == null) {
      throw new BusinessException(ErrorStatus.USER_SOCIAL_PROVIDER_REQUIRED);
    }

    if (request.getSocialId() == null) {
      throw new BusinessException(ErrorStatus.USER_SOCIAL_ID_REQUIRED);
    }
  }

  // enum 변환 (안전 처리)
  private SocialProvider parseProvider(String provider) {
    try {
      return SocialProvider.valueOf(provider.toUpperCase());
    } catch (Exception e) {
      throw new BusinessException(ErrorStatus.USER_SOCIAL_PROVIDER_INVALID);
    }
  }
}
