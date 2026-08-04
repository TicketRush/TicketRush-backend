package com.ticketrush.boundedcontext.auth.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketrush.boundedcontext.auth.app.dto.request.DevTokenIssueRequest;
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

@ExtendWith(MockitoExtension.class)
class DevTokenIssueUseCaseTest {

  @Mock private UserServiceClient userServiceClient;

  @Mock private JwtTokenProvider jwtTokenProvider;

  @InjectMocks private DevTokenIssueUseCase devTokenIssueUseCase;

  @Test
  @DisplayName("userId로 회원 정보를 조회한 뒤 실제 권한으로 테스트용 토큰을 발급한다")
  void issueDevToken_success() {
    // given
    Long userId = 1L;
    String email = "test@example.com";
    String passwordHash = "$2a$10$encoded-password";
    String role = "MEMBER";

    DevTokenIssueRequest request = new DevTokenIssueRequest(userId);

    UserServiceAuthInfoResponse userInfo =
        new UserServiceAuthInfoResponse(userId, email, passwordHash, role);

    when(userServiceClient.getUserAuthInfoByUserId(userId)).thenReturn(userInfo);
    when(jwtTokenProvider.createAccessToken(userId, role)).thenReturn("access-token");
    when(jwtTokenProvider.createRefreshToken(userId)).thenReturn("refresh-token");

    // when
    LoginResponse response = devTokenIssueUseCase.execute(request);

    // then
    assertThat(response.userId()).isEqualTo(userId);
    assertThat(response.email()).isEqualTo(email);
    assertThat(response.role()).isEqualTo(role);
    assertThat(response.accessToken()).isEqualTo("access-token");
    assertThat(response.refreshToken()).isEqualTo("refresh-token");

    verify(userServiceClient).getUserAuthInfoByUserId(userId);
    verify(jwtTokenProvider).createAccessToken(userId, role);
    verify(jwtTokenProvider).createRefreshToken(userId);
  }

  @Test
  @DisplayName("user-service에서 회원 정보를 찾지 못하면 테스트 토큰 발급에 실패한다")
  void issueDevToken_fail_whenUserServiceThrowsException() {
    // given
    Long userId = 999L;
    DevTokenIssueRequest request = new DevTokenIssueRequest(userId);

    when(userServiceClient.getUserAuthInfoByUserId(userId))
        .thenThrow(new BusinessException(ErrorStatus.AUTH_LOGIN_FAILED));

    // when & then
    assertThatThrownBy(() -> devTokenIssueUseCase.execute(request))
        .isInstanceOf(BusinessException.class);

    verify(userServiceClient).getUserAuthInfoByUserId(userId);
    verify(jwtTokenProvider, never()).createAccessToken(anyLong(), anyString());
    verify(jwtTokenProvider, never()).createRefreshToken(anyLong());
  }

  @Test
  @DisplayName("지원하지 않는 role이면 테스트 토큰 발급에 실패한다")
  void issueDevToken_fail_whenUnsupportedRole() {
    // given
    Long userId = 1L;
    String email = "test@example.com";
    String passwordHash = "$2a$10$encoded-password";
    String unsupportedRole = "MANAGER";

    DevTokenIssueRequest request = new DevTokenIssueRequest(userId);

    UserServiceAuthInfoResponse userInfo =
        new UserServiceAuthInfoResponse(userId, email, passwordHash, unsupportedRole);

    when(userServiceClient.getUserAuthInfoByUserId(userId)).thenReturn(userInfo);

    // when & then
    assertThatThrownBy(() -> devTokenIssueUseCase.execute(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("지원하지 않는 사용자 역할입니다");

    verify(userServiceClient).getUserAuthInfoByUserId(userId);
    verify(jwtTokenProvider, never()).createAccessToken(anyLong(), anyString());
    verify(jwtTokenProvider, never()).createRefreshToken(anyLong());
  }
}
