package com.ticketrush.boundedcontext.user.in.api.v1;

import com.ticketrush.boundedcontext.user.app.dto.request.SignupRequest;
import com.ticketrush.boundedcontext.user.app.dto.request.SocialLoginRequest;
import com.ticketrush.boundedcontext.user.app.dto.response.EmailExistsResponse;
import com.ticketrush.boundedcontext.user.app.dto.response.SignupResponse;
import com.ticketrush.boundedcontext.user.app.dto.response.SocialLoginResponse;
import com.ticketrush.boundedcontext.user.app.facade.UserFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User", description = "회원 로그인 API")
@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

  private final UserFacade userFacade;

  @Operation(summary = "소셜로그인 회원 등록", description = "소셜로그인을 통해 회원을 등록합니다.")
  @PostMapping("/social-login")
  public ResponseEntity<ApiResponse<SocialLoginResponse>> socialLogin(
      @RequestBody SocialLoginRequest request) {
    SocialLoginResponse response = userFacade.socialLogin(request);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(summary = "이메일 중복 확인", description = "이메일로 가입된 회원이 존재하는지 확인합니다.")
  @GetMapping("/exists/email")
  public ResponseEntity<ApiResponse<EmailExistsResponse>> existsByEmail(
      @RequestParam(required = false) String email) {
    EmailExistsResponse response = userFacade.existsByEmail(email);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(summary = "회원가입", description = "이메일과 비밀번호로 회원가입합니다.")
  @PostMapping("/signup")
  public ResponseEntity<ApiResponse<SignupResponse>> signup(@RequestBody SignupRequest request) {
    SignupResponse response = userFacade.signup(request);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }
}
