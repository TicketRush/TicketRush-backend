package com.ticketrush.boundedcontext.auth.app.usecase;

import com.ticketrush.boundedcontext.auth.app.dto.request.LoginRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.login.LoginResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.login.UserServiceAuthInfoResponse;
import com.ticketrush.boundedcontext.auth.out.apiclient.UserServiceClient;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.security.JwtTokenProvider;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LoginUseCase {

  private final UserServiceClient userServiceClient;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenProvider jwtTokenProvider;

  public LoginResponse execute(LoginRequest request) {
    UserServiceAuthInfoResponse user = userServiceClient.getUserAuthInfoByEmail(request.email());

    if (!passwordEncoder.matches(request.password(), user.password())) {
      throw new BusinessException(ErrorStatus.AUTH_LOGIN_FAILED);
    }

    String normalizedRole = normalizeRole(user.role());

    String accessToken = jwtTokenProvider.createAccessToken(user.userId(), normalizedRole);

    String refreshToken = jwtTokenProvider.createRefreshToken(user.userId());

    return new LoginResponse(
      user.userId(),
      user.email(),
      normalizedRole,
      accessToken,
      refreshToken
    );
  }

  private String normalizeRole(String role) {
    return switch (role) {
      case "MEMBER" -> "USER";
      case "ADMIN" -> "ADMIN";
      default -> throw new IllegalArgumentException("지원하지 않는 사용자 역할입니다: " + role);
    };
  }
}