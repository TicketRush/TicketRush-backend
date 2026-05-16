package com.ticketrush.boundedcontext.auth.in.api.v1;

import com.ticketrush.boundedcontext.auth.app.dto.request.SignupEmailAuthNumberSendRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.SignupEmailAuthNumberSendResponse;
import com.ticketrush.boundedcontext.auth.app.facade.AuthFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthFacade authFacade;

  @Operation(summary = "회원가입 이메일 인증 번호 발송", description = "회원가입 전 이메일 중복 확인 후 인증 번호를 발송합니다.")
  @PostMapping("/signup/email-verification/send")
  public SignupEmailAuthNumberSendResponse sendSignupEmailAuthNumber(
      @Valid @RequestBody SignupEmailAuthNumberSendRequest request) {
    return authFacade.sendSignupEmailAuthNumber(request);
  }
}
