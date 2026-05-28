package com.ticketrush.boundedcontext.user.in.api.v1;

import com.ticketrush.boundedcontext.user.app.dto.request.UserAuthInfoRequest;
import com.ticketrush.boundedcontext.user.app.dto.response.UserAuthInfoResponse;
import com.ticketrush.boundedcontext.user.app.facade.UserFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Internal User", description = "내부 통신용 회원 API")
@RestController
@RequestMapping("/api/v1/internal/user")
@RequiredArgsConstructor
public class InternalUserController {

  private final UserFacade userFacade;

  @Operation(summary = "로그인 검증용 회원 정보 조회", description = "이메일로 로그인 검증에 필요한 회원 정보를 조회합니다.")
  @PostMapping("/auth-info")
  public ResponseEntity<ApiResponse<UserAuthInfoResponse>> getUserAuthInfoByEmail(
      @RequestBody UserAuthInfoRequest request) {
    UserAuthInfoResponse response = userFacade.getUserAuthInfoByEmail(request.email());

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }
}
