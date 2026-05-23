package com.ticketrush.boundedcontext.auth.app.usecase;

import com.ticketrush.boundedcontext.auth.app.dto.response.signup.SignupEmailVerificationCheckResponse;
import com.ticketrush.boundedcontext.auth.out.repository.RedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SignupEmailVerificationCheckUseCase {

  private final RedisRepository redisRepository;

  public SignupEmailVerificationCheckResponse execute(String email) {
    boolean verified = redisRepository.isSignupEmailAuthVerified(email);

    log.info("[이메일 인증 완료 여부 조회] email={}, verified={}", email, verified);

    return new SignupEmailVerificationCheckResponse(verified);
  }
}
