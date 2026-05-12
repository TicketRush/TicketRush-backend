package com.ticketrush.boundedcontext.booking.in.api.v1;

import com.ticketrush.boundedcontext.booking.app.dto.request.BookingPendingRequest;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPendingResponse;
import com.ticketrush.boundedcontext.booking.app.facade.BookingFacade;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.security.CustomUserDetails;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Booking", description = "예약 관련 API")
@RestController
@RequestMapping("/api/v1/booking")
@RequiredArgsConstructor
public class BookingController {

  private final BookingFacade bookingFacade;

  @Operation(
      summary = "예매 생성 (결제 대기 상태)",
      description = "사용자가 공연 좌석을 선택하면 예매를 생성하고 상태를 PENDING으로 설정합니다.")
  @PostMapping
  public ResponseEntity<ApiResponse<BookingPendingResponse>> createPendingBooking(
      @AuthenticationPrincipal CustomUserDetails user,
      @Valid @RequestBody BookingPendingRequest request) {
    if (user == null) {
      throw new BusinessException(ErrorStatus.UNAUTHORIZED);
    }

    Booking booking =
        bookingFacade.createBooking(user.getUserId(), request.performanceId(), request.seatId());

    return ApiResponse.onSuccess(SuccessStatus.CREATED, BookingPendingResponse.from(booking));
  }
}
