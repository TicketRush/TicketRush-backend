package com.ticketrush.boundedcontext.booking.in.api.v1;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingInternalResponse;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetInternalUseCase;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden // 내부 전용 API — 공개 OpenAPI 문서(/v3/api-docs)에 노출하지 않는다.
@Tag(name = "BookingInternal", description = "예매 내부 통신 API")
@RestController
@RequestMapping("/api/v1/internal/booking")
@RequiredArgsConstructor
public class BookingInternalController {

  private final BookingGetInternalUseCase bookingGetInternalUseCase;

  @Operation(summary = "예매 소유자/상태 내부 조회", description = "예매의 소유자와 상태를 반환합니다.")
  @GetMapping("/{bookingId}")
  public ResponseEntity<ApiResponse<BookingInternalResponse>> getBookingInternal(
      @PathVariable Long bookingId) {
    BookingInternalResponse response = bookingGetInternalUseCase.execute(bookingId);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }
}
