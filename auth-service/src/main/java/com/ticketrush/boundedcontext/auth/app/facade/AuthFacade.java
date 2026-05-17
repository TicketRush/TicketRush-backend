package com.ticketrush.boundedcontext.auth.app.facade;

import com.ticketrush.boundedcontext.auth.app.dto.request.SignupEmailAuthNumberSendRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.SignupEmailAuthNumberSendResponse;
import com.ticketrush.boundedcontext.auth.app.usecase.SignupEmailAuthNumberSendUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthFacade {

  private final SignupEmailAuthNumberSendUseCase signupEmailAuthNumberSendUseCase;

  public SignupEmailAuthNumberSendResponse sendSignupEmailAuthNumber(
      SignupEmailAuthNumberSendRequest request) {
    return signupEmailAuthNumberSendUseCase.execute(request);
  }
}
