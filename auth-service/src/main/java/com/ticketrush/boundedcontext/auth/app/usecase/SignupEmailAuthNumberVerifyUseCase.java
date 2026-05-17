package com.ticketrush.boundedcontext.auth.app.usecase;

import com.ticketrush.boundedcontext.auth.app.dto.request.SignupEmailAuthNumberVerifyRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.SignupEmailAuthNumberVerifyResponse;
import com.ticketrush.boundedcontext.auth.out.repository.RedisRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SignupEmailAuthNumberVerifyUseCase {

  private final RedisRepository redisRepository;

  public SignupEmailAuthNumberVerifyResponse execute(SignupEmailAuthNumberVerifyRequest request) {
    String email = request.email();
    String inputAuthNumber = request.authNumber();

    String savedAuthNumber = redisRepository.getSignupEmailAuthNumber(email);

    if (savedAuthNumber == null) {
      throw new BusinessException(ErrorStatus.AUTH_NUMBER_VERIFY_NOT_FOUND);
    }

    if (!savedAuthNumber.equals(inputAuthNumber)) {
      throw new BusinessException(ErrorStatus.AUTH_NUMBER_NOT_MATCH);
    }

    redisRepository.saveSignupEmailAuthVerified(email);
    redisRepository.deleteSignupEmailAuthNumber(email);

    return new SignupEmailAuthNumberVerifyResponse(true);
  }
}
