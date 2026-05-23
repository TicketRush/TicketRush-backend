package com.ticketrush.boundedcontext.user.app.facade;

import com.ticketrush.boundedcontext.user.app.dto.request.SignupRequest;
import com.ticketrush.boundedcontext.user.app.dto.request.SocialLoginRequest;
import com.ticketrush.boundedcontext.user.app.dto.response.EmailExistsResponse;
import com.ticketrush.boundedcontext.user.app.dto.response.SignupResponse;
import com.ticketrush.boundedcontext.user.app.dto.response.SocialLoginResponse;
import com.ticketrush.boundedcontext.user.app.usecase.EmailExistsUseCase;
import com.ticketrush.boundedcontext.user.app.usecase.SignupUseCase;
import com.ticketrush.boundedcontext.user.app.usecase.SocialLoginUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserFacade {

  private final SocialLoginUseCase socialLoginUseCase;
  private final EmailExistsUseCase emailExistsUseCase;
  private final SignupUseCase signupUseCase;

  // 소셜 로그인
  public SocialLoginResponse socialLogin(SocialLoginRequest request) {
    return socialLoginUseCase.execute(request);
  }

  // 이메일 존재하는지 검증
  public EmailExistsResponse existsByEmail(String email) {
    return emailExistsUseCase.execute(email);
  }

  // 회원가입
  public SignupResponse signup(SignupRequest request) {
    return signupUseCase.execute(request);
  }
}
