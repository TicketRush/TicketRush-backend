package com.ticketrush.boundedcontext.auth.app.usecase;

import com.ticketrush.boundedcontext.auth.app.dto.request.DevTokenIssueRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.login.LoginResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.login.UserServiceAuthInfoResponse;
import com.ticketrush.boundedcontext.auth.out.apiclient.UserServiceClient;
import com.ticketrush.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("local")
@Service
@RequiredArgsConstructor
@Transactional
public class DevTokenIssueUseCase {

  private final UserServiceClient userServiceClient;
  private final JwtTokenProvider jwtTokenProvider;

  public LoginResponse execute(DevTokenIssueRequest request) {
    UserServiceAuthInfoResponse user = userServiceClient.getUserAuthInfoByUserId(request.userId());

    String role = validateRole(user.role());

    String accessToken = jwtTokenProvider.createAccessToken(user.userId(), role);

    String refreshToken = jwtTokenProvider.createRefreshToken(user.userId());

    return new LoginResponse(user.userId(), user.email(), role, accessToken, refreshToken);
  }

  private String validateRole(String role) {
    if (!"MEMBER".equals(role) && !"ADMIN".equals(role)) {
      throw new com.ticketrush.global.exception.BusinessException(
          com.ticketrush.global.status.ErrorStatus.BAD_REQUEST, "지원하지 않는 사용자 역할입니다: " + role);
    }

    return role;
  }
}
