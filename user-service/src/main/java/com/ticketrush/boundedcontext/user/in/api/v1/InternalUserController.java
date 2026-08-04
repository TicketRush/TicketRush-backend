package com.ticketrush.boundedcontext.user.in.api.v1;

import com.ticketrush.boundedcontext.user.app.dto.request.UserAuthInfoRequest;
import com.ticketrush.boundedcontext.user.app.dto.response.UserAuthInfoResponse;
import com.ticketrush.boundedcontext.user.app.dto.response.UserSummaryResponse;
import com.ticketrush.boundedcontext.user.app.facade.UserFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Internal User", description = "내부 통신용 회원 API")
// @Size가 발화하려면 클래스에 @Validated가 있어야 한다 — 메서드 파라미터 제약은 이 프록시가 없으면 조용히 무시된다.
@Validated
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

  @Operation(summary = "테스트 토큰 발급용 회원 정보 조회", description = "userId로 토큰 발급에 필요한 회원 정보를 조회합니다.")
  @GetMapping("/{userId}/auth-info")
  public ResponseEntity<ApiResponse<UserAuthInfoResponse>> getUserAuthInfoByUserId(
      @PathVariable Long userId) {
    UserAuthInfoResponse response = userFacade.getUserAuthInfoByUserId(userId);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(
      summary = "회원 요약 벌크 조회",
      description =
          """
          userId 목록으로 이름·이메일을 한 번에 조회합니다. 다른 서비스가 조회 응답에 예매자 정보를 보강할 때 씁니다 (#561).

          **존재하지 않는 userId는 예외 없이 결과에서 빠집니다.** 호출 측은 자기 데이터에 딸린 userId를 그대로 넘기는데,
          탈퇴 회원 한 건 때문에 목록 조회 전체가 실패해선 안 되기 때문입니다.

          이름·이메일은 소셜 가입 경로에서 비어 있을 수 있어 null로 내려갈 수 있습니다.
          """)
  @GetMapping("/summaries")
  public ResponseEntity<ApiResponse<List<UserSummaryResponse>>> getUserSummaries(
      @RequestParam @Size(min = 1, max = 50) List<Long> userIds) {
    List<UserSummaryResponse> response = userFacade.getUserSummaries(userIds);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }
}
