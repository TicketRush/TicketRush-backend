package com.ticketrush.boundedcontext.auth.app.usecase;

import com.ticketrush.boundedcontext.auth.out.repository.RedisRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignupEmailVerificationConsumeUseCase {

  private final RedisRepository redisRepository;

  public void execute(String email) {
    boolean consumed = redisRepository.consumeSignupEmailAuthVerified(email);

    if (!consumed) {
      throw new BusinessException(ErrorStatus.EMAIL_AUTH_NOT_VERIFIED);
    }

    log.info("[회원가입 이메일 인증 완료 상태 소비] email={}", email);
  }
}
