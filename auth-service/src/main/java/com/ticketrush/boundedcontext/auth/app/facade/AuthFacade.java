package com.ticketrush.boundedcontext.auth.app.facade;

import com.ticketrush.boundedcontext.auth.app.dto.request.SignupEmailAuthNumberSendRequest;
import com.ticketrush.boundedcontext.auth.app.dto.request.SignupEmailAuthNumberVerifyRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.SignupEmailAuthNumberSendResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.SignupEmailAuthNumberVerifyResponse;
import com.ticketrush.boundedcontext.auth.app.usecase.SignupEmailAuthNumberSendUseCase;
import com.ticketrush.boundedcontext.auth.app.usecase.SignupEmailAuthNumberVerifyUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthFacade {

  private final SignupEmailAuthNumberSendUseCase signupEmailAuthNumberSendUseCase;
  private final SignupEmailAuthNumberVerifyUseCase signupEmailAuthNumberVerifyUseCase;

  // Email 인증번호 발송
  public SignupEmailAuthNumberSendResponse sendSignupEmailAuthNumber(
      SignupEmailAuthNumberSendRequest request) {
    return signupEmailAuthNumberSendUseCase.execute(request);
  }

  // Email 인증번호 확인
  public SignupEmailAuthNumberVerifyResponse verifySignupEmailAuthNumber(
      SignupEmailAuthNumberVerifyRequest request) {
    return signupEmailAuthNumberVerifyUseCase.execute(request);
  }
}
