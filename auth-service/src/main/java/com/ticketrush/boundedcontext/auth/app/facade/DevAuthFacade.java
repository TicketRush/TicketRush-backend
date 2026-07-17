package com.ticketrush.boundedcontext.auth.app.facade;

import com.ticketrush.boundedcontext.auth.app.dto.request.DevTokenIssueRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.login.LoginResponse;
import com.ticketrush.boundedcontext.auth.app.usecase.DevTokenIssueUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("local")
@Component
@RequiredArgsConstructor
public class DevAuthFacade {

  private final DevTokenIssueUseCase devTokenIssueUseCase;

  public LoginResponse issueDevToken(DevTokenIssueRequest request) {
    return devTokenIssueUseCase.execute(request);
  }
}
