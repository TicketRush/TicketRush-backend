package com.ticketrush.boundedcontext.ticket.in.api.v1;

import com.ticketrush.boundedcontext.ticket.app.dto.request.EntryVerifyRequest;
import com.ticketrush.boundedcontext.ticket.app.dto.response.EntryCheckInResponse;
import com.ticketrush.boundedcontext.ticket.app.dto.response.EntryVerifyResponse;
import com.ticketrush.boundedcontext.ticket.app.usecase.EntryCheckInUseCase;
import com.ticketrush.boundedcontext.ticket.app.usecase.EntryVerifyUseCase;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Entry", description = "입장(검표) 관련 API")
@RestController
@RequestMapping("/api/v1/entries")
@RequiredArgsConstructor
public class EntryController {

  private final EntryVerifyUseCase entryVerifyUseCase;
  private final EntryCheckInUseCase entryCheckInUseCase;

  @Operation(
      summary = "QR 토큰 검증",
      description =
          "스캔한 QR payload의 서명·만료와 입장권/예매 상태를 검증해 입장 가능 여부를 반환합니다(상태 변경 없음). ADMIN 권한이 필요합니다.")
  @PostMapping("/verify")
  public ResponseEntity<ApiResponse<EntryVerifyResponse>> verify(
      @Valid @RequestBody EntryVerifyRequest request) {
    EntryVerifyResponse response = entryVerifyUseCase.execute(request.token());
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(
      summary = "입장 처리",
      description =
          "검증을 통과한 입장권을 사용 완료(USED)로 전이하고 입장 시각을 기록합니다. 동일 QR 중복 스캔은 한 번만 성공합니다. ADMIN 권한이 필요합니다.")
  @PostMapping("/check-in")
  public ResponseEntity<ApiResponse<EntryCheckInResponse>> checkIn(
      @Valid @RequestBody EntryVerifyRequest request) {
    EntryCheckInResponse response = entryCheckInUseCase.execute(request.token());
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }
}
