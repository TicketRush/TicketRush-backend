package com.ticketrush.boundedcontext.auth.app.facade;

import com.ticketrush.boundedcontext.auth.app.dto.request.LoginRequest;
import com.ticketrush.boundedcontext.auth.app.dto.request.SignupEmailAuthNumberSendRequest;
import com.ticketrush.boundedcontext.auth.app.dto.request.SignupEmailAuthNumberVerifyRequest;
import com.ticketrush.boundedcontext.auth.app.dto.request.SignupEmailVerificationConsumeRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.login.LoginResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.signup.SignupEmailAuthNumberSendResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.signup.SignupEmailAuthNumberVerifyResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.signup.SignupEmailVerificationCheckResponse;
import com.ticketrush.boundedcontext.auth.app.usecase.LoginUseCase;
import com.ticketrush.boundedcontext.auth.app.usecase.SignupEmailAuthNumberSendUseCase;
import com.ticketrush.boundedcontext.auth.app.usecase.SignupEmailAuthNumberVerifyUseCase;
import com.ticketrush.boundedcontext.auth.app.usecase.SignupEmailVerificationCheckUseCase;
import com.ticketrush.boundedcontext.auth.app.usecase.SignupEmailVerificationConsumeUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthFacade {

  private final SignupEmailAuthNumberSendUseCase signupEmailAuthNumberSendUseCase;
  private final SignupEmailAuthNumberVerifyUseCase signupEmailAuthNumberVerifyUseCase;
  private final SignupEmailVerificationCheckUseCase signupEmailVerificationCheckUseCase;
  private final SignupEmailVerificationConsumeUseCase signupEmailVerificationConsumeUseCase;
  private final LoginUseCase loginUseCase;

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

  // Email 일치여부 확인
  public SignupEmailVerificationCheckResponse checkSignupEmailVerification(String email) {
    return signupEmailVerificationCheckUseCase.execute(email);
  }

  public void consumeSignupEmailAuthVerified(SignupEmailVerificationConsumeRequest request) {
    signupEmailVerificationConsumeUseCase.execute(request.email());
  }

  public LoginResponse login(LoginRequest request) {
    return loginUseCase.execute(request);
  }
}
