package com.ticketrush.boundedcontext.auth.in.api.v1;

import com.ticketrush.boundedcontext.auth.app.dto.request.DevTokenIssueRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.login.LoginResponse;
import com.ticketrush.boundedcontext.auth.app.facade.DevAuthFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile({"local", "dev"})
@Tag(name = "Dev Auth", description = "개발 및 테스트용 인증 API")
@RestController
@RequestMapping("/api/v1/dev/auth")
@RequiredArgsConstructor
public class DevTokenController {

  private final DevAuthFacade devAuthFacade;

  @Operation(
      summary = "테스트용 토큰 발급",
      description = "프론트엔드 테스트를 위해 userId 기준으로 access token과 refresh token을 발급합니다.")
  @PostMapping("/token")
  public ResponseEntity<ApiResponse<LoginResponse>> issueDevToken(
      @Valid @RequestBody DevTokenIssueRequest request) {
    LoginResponse response = devAuthFacade.issueDevToken(request);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }
}
