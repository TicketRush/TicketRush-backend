package com.ticketrush.boundedcontext.auth.in.api.v1;

import com.ticketrush.boundedcontext.auth.app.dto.request.SignupEmailAuthNumberSendRequest;
import com.ticketrush.boundedcontext.auth.app.dto.request.SignupEmailAuthNumberVerifyRequest;
import com.ticketrush.boundedcontext.auth.app.dto.request.SignupEmailVerificationConsumeRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.signup.SignupEmailAuthNumberSendResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.signup.SignupEmailAuthNumberVerifyResponse;
import com.ticketrush.boundedcontext.auth.app.dto.response.signup.SignupEmailVerificationCheckResponse;
import com.ticketrush.boundedcontext.auth.app.facade.AuthFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthFacade authFacade;

  @Operation(summary = "회원가입 이메일 인증 번호 발송", description = "회원가입 전 이메일 중복 확인 후 인증 번호를 발송합니다.")
  @PostMapping("/signup/email-verification/send")
  public ResponseEntity<ApiResponse<SignupEmailAuthNumberSendResponse>> sendSignupEmailAuthNumber(
      @Valid @RequestBody SignupEmailAuthNumberSendRequest request) {
    SignupEmailAuthNumberSendResponse response = authFacade.sendSignupEmailAuthNumber(request);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(summary = "회원가입 이메일 인증 번호 확인", description = "회원가입 이메일로 발송된 인증 번호가 일치하는지 확인합니다.")
  @PostMapping("/signup/email-verification/verify")
  public ResponseEntity<ApiResponse<SignupEmailAuthNumberVerifyResponse>>
      verifySignupEmailAuthNumber(@Valid @RequestBody SignupEmailAuthNumberVerifyRequest request) {
    SignupEmailAuthNumberVerifyResponse response = authFacade.verifySignupEmailAuthNumber(request);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(summary = "회원가입 이메일 인증 완료 여부 조회", description = "회원가입 전 이메일 인증 완료 여부를 조회합니다.")
  @GetMapping("/signup/email-verification/verified")
  public ResponseEntity<ApiResponse<SignupEmailVerificationCheckResponse>>
      checkSignupEmailVerification(@RequestParam String email) {
    SignupEmailVerificationCheckResponse response = authFacade.checkSignupEmailVerification(email);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(summary = "회원가입 이메일 인증 완료 상태 소비", description = "회원가입 진행 전 이메일 인증 완료 상태를 확인하고 삭제합니다.")
  @PostMapping("/signup/email-verification/consume")
  public ResponseEntity<ApiResponse<Void>> consumeSignupEmailVerification(
      @Valid @RequestBody SignupEmailVerificationConsumeRequest request) {
    authFacade.consumeSignupEmailAuthVerified(request);

    return ApiResponse.onSuccess(SuccessStatus.OK, (Void) null);
  }
}
