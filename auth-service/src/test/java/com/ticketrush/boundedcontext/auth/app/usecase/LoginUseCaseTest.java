package com.ticketrush.boundedcontext.auth.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketrush.boundedcontext.auth.app.dto.request.LoginRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.login.LoginResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.login.UserServiceAuthInfoResponse;
import com.ticketrush.boundedcontext.auth.out.apiclient.UserServiceClient;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.security.JwtTokenProvider;
import com.ticketrush.global.status.ErrorStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class LoginUseCaseTest {

  @Mock private UserServiceClient userServiceClient;

  @Mock private PasswordEncoder passwordEncoder;

  @Mock private JwtTokenProvider jwtTokenProvider;

  @InjectMocks private LoginUseCase loginUseCase;

  @Test
  @DisplayName("이메일과 비밀번호가 일치하면 실제 권한으로 로그인 토큰을 발급한다")
  void login_success() {
    // given
    String email = "test@example.com";
    String rawPassword = "Password123!";
    String passwordHash = "$2a$10$encoded-password";
    Long userId = 1L;
    String role = "MEMBER";

    LoginRequest request = new LoginRequest(email, rawPassword);

    UserServiceAuthInfoResponse userInfo =
        new UserServiceAuthInfoResponse(userId, email, passwordHash, role);

    when(userServiceClient.getUserAuthInfoByEmail(email)).thenReturn(userInfo);
    when(passwordEncoder.matches(rawPassword, passwordHash)).thenReturn(true);
    when(jwtTokenProvider.createAccessToken(userId, role)).thenReturn("access-token");
    when(jwtTokenProvider.createRefreshToken(userId)).thenReturn("refresh-token");

    // when
    LoginResponse response = loginUseCase.execute(request);

    // then
    assertThat(response.userId()).isEqualTo(userId);
    assertThat(response.email()).isEqualTo(email);
    assertThat(response.role()).isEqualTo(role);
    assertThat(response.accessToken()).isEqualTo("access-token");
    assertThat(response.refreshToken()).isEqualTo("refresh-token");

    verify(userServiceClient).getUserAuthInfoByEmail(email);
    verify(passwordEncoder).matches(rawPassword, passwordHash);
    verify(jwtTokenProvider).createAccessToken(userId, role);
    verify(jwtTokenProvider).createRefreshToken(userId);
  }

  @Test
  @DisplayName("비밀번호가 일치하지 않으면 로그인에 실패한다")
  void login_fail_whenPasswordMismatch() {
    // given
    String email = "test@example.com";
    String rawPassword = "WrongPassword123!";
    String passwordHash = "$2a$10$encoded-password";
    Long userId = 1L;
    String role = "MEMBER";

    LoginRequest request = new LoginRequest(email, rawPassword);

    UserServiceAuthInfoResponse userInfo =
        new UserServiceAuthInfoResponse(userId, email, passwordHash, role);

    when(userServiceClient.getUserAuthInfoByEmail(email)).thenReturn(userInfo);
    when(passwordEncoder.matches(rawPassword, passwordHash)).thenReturn(false);

    // when & then
    assertThatThrownBy(() -> loginUseCase.execute(request)).isInstanceOf(BusinessException.class);

    verify(userServiceClient).getUserAuthInfoByEmail(email);
    verify(passwordEncoder).matches(rawPassword, passwordHash);
    verify(jwtTokenProvider, never()).createAccessToken(anyLong(), anyString());
    verify(jwtTokenProvider, never()).createRefreshToken(anyLong());
  }

  @Test
  @DisplayName("user-service에서 회원 정보를 찾지 못하면 로그인에 실패한다")
  void login_fail_whenUserServiceThrowsException() {
    // given
    String email = "notfound@example.com";
    String password = "Password123!";

    LoginRequest request = new LoginRequest(email, password);

    when(userServiceClient.getUserAuthInfoByEmail(email))
        .thenThrow(new BusinessException(ErrorStatus.AUTH_LOGIN_FAILED));

    // when & then
    assertThatThrownBy(() -> loginUseCase.execute(request)).isInstanceOf(BusinessException.class);

    verify(userServiceClient).getUserAuthInfoByEmail(email);
    verify(passwordEncoder, never()).matches(anyString(), anyString());
    verify(jwtTokenProvider, never()).createAccessToken(anyLong(), anyString());
    verify(jwtTokenProvider, never()).createRefreshToken(anyLong());
  }

  @Test
  @DisplayName("지원하지 않는 role이면 로그인 토큰 발급에 실패한다")
  void login_fail_whenUnsupportedRole() {
    // given
    String email = "test@example.com";
    String rawPassword = "Password123!";
    String passwordHash = "$2a$10$encoded-password";
    Long userId = 1L;
    String unsupportedRole = "MANAGER";

    LoginRequest request = new LoginRequest(email, rawPassword);

    UserServiceAuthInfoResponse userInfo =
        new UserServiceAuthInfoResponse(userId, email, passwordHash, unsupportedRole);

    when(userServiceClient.getUserAuthInfoByEmail(email)).thenReturn(userInfo);
    when(passwordEncoder.matches(rawPassword, passwordHash)).thenReturn(true);

    // when & then
    assertThatThrownBy(() -> loginUseCase.execute(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("지원하지 않는 사용자 역할입니다");

    verify(userServiceClient).getUserAuthInfoByEmail(email);
    verify(passwordEncoder).matches(rawPassword, passwordHash);
    verify(jwtTokenProvider, never()).createAccessToken(anyLong(), anyString());
    verify(jwtTokenProvider, never()).createRefreshToken(anyLong());
  }
}
