package com.ticketrush.boundedcontext.booking.in.api.v1;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingInternalResponse;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetInternalUseCase;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "BookingInternal", description = "예매 내부 통신 API")
@RestController
@RequestMapping("/api/v1/booking")
@RequiredArgsConstructor
public class BookingInternalController {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  private final BookingGetInternalUseCase bookingGetInternalUseCase;

  @Value("${gateway.internal-token}")
  private String internalToken;

  @Operation(summary = "예매 소유자/상태 내부 조회", description = "내부 토큰을 검증한 뒤 예매의 소유자와 상태를 반환합니다.")
  @GetMapping("/internal/{bookingId}")
  public ResponseEntity<ApiResponse<BookingInternalResponse>> getBookingInternal(
      @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String internalTokenHeader,
      @PathVariable Long bookingId) {
    validateInternalToken(internalTokenHeader);
    BookingInternalResponse response = bookingGetInternalUseCase.execute(bookingId);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  private void validateInternalToken(String internalTokenHeader) {
    if (!internalToken.equals(internalTokenHeader)) {
      throw new BusinessException(ErrorStatus.FORBIDDEN);
    }
  }
}
