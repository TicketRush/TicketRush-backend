package com.ticketrush.boundedcontext.auth.in.api.v1;

import com.ticketrush.boundedcontext.auth.app.dto.request.SocialOauthLoginRequest;
import com.ticketrush.boundedcontext.auth.app.dto.request.TokenReissueRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.OauthLoginResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.TokenReissueResponse;
import com.ticketrush.boundedcontext.auth.app.facade.AuthFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.security.CustomUserDetails;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthFacade authFacade;

  @Operation(summary = "OAuth2 로그인 URL 조회", description = "OAuth2 로그인 URL을 반환합니다.")
  @GetMapping("/oauth/{provider}/url")
  public ResponseEntity<ApiResponse<String>> getOAuthLoginUrl(
      @PathVariable String provider, @RequestParam String redirectUri) {

    String url = authFacade.getOAuthLoginUrl(provider, redirectUri);
    return ApiResponse.onSuccess(SuccessStatus.OK, url);
  }

  @Operation(summary = "소셜 로그인", description = "인가 코드를 통해 사용자 인증 후 JWT 토큰(access, refresh)을 발급합니다.")
  @PostMapping("/social/login")
  public ResponseEntity<ApiResponse<OauthLoginResponse>> socialLogin(
      @RequestBody @Valid SocialOauthLoginRequest request) {

    OauthLoginResponse response = authFacade.socialLogin(request);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(
      summary = "JWT 토큰 재발급",
      description = "refresh token을 검증한 뒤 access token과 refresh token을 재발급합니다.")
  @PostMapping("/reissue")
  public ResponseEntity<ApiResponse<TokenReissueResponse>> reissue(
      @RequestBody @Valid TokenReissueRequest request) {

    TokenReissueResponse response = authFacade.reissue(request.refreshToken());
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(summary = "소셜 로그아웃", description = "Refresh token 삭제를 통해 로그아웃합니다.")
  @PostMapping("/logout")
  public ResponseEntity<ApiResponse<Void>> logout(@AuthenticationPrincipal CustomUserDetails user) {

    if (user == null) {
      throw new BusinessException(ErrorStatus.UNAUTHORIZED);
    }

    authFacade.logout(user.getUserId());

    return ApiResponse.onSuccess(SuccessStatus.OK);
  }
}
