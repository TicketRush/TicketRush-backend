package com.ticketrush.boundedcontext.auth.app.usecase;

import com.ticketrush.boundedcontext.auth.app.dto.request.SignupEmailAuthNumberVerifyRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.signup.SignupEmailAuthNumberVerifyResponse;
import com.ticketrush.boundedcontext.auth.out.repository.RedisRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SignupEmailAuthNumberVerifyUseCase {

  private static final int MAX_VERIFY_ATTEMPT_COUNT = 5;

  private final RedisRepository redisRepository;

  public SignupEmailAuthNumberVerifyResponse execute(SignupEmailAuthNumberVerifyRequest request) {
    String email = request.email();
    String inputAuthNumber = request.authNumber();

    String savedAuthNumber = redisRepository.getSignupEmailAuthNumber(email);

    if (savedAuthNumber == null) {
      throw new BusinessException(ErrorStatus.AUTH_NUMBER_VERIFY_NOT_FOUND);
    }

    if (redisRepository.getSignupEmailAuthVerifyAttemptCount(email) >= MAX_VERIFY_ATTEMPT_COUNT) {
      redisRepository.deleteSignupEmailAuthNumber(email);
      redisRepository.deleteSignupEmailAuthVerifyAttempt(email);
      throw new BusinessException(ErrorStatus.AUTH_NUMBER_VERIFY_ATTEMPT_EXCEEDED);
    }

    if (!savedAuthNumber.equals(inputAuthNumber)) {
      long attemptCount = redisRepository.increaseSignupEmailAuthVerifyAttempt(email);

      if (attemptCount >= MAX_VERIFY_ATTEMPT_COUNT) {
        redisRepository.deleteSignupEmailAuthNumber(email);
        redisRepository.deleteSignupEmailAuthVerifyAttempt(email);
        throw new BusinessException(ErrorStatus.AUTH_NUMBER_VERIFY_ATTEMPT_EXCEEDED);
      }

      throw new BusinessException(ErrorStatus.AUTH_NUMBER_NOT_MATCH);
    }

    redisRepository.saveSignupEmailAuthVerified(email);
    redisRepository.deleteSignupEmailAuthNumber(email);
    redisRepository.deleteSignupEmailAuthVerifyAttempt(email);

    return new SignupEmailAuthNumberVerifyResponse();
  }
}
