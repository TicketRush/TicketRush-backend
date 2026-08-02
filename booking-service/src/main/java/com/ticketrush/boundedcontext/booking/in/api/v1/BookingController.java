package com.ticketrush.boundedcontext.booking.in.api.v1;

import com.ticketrush.boundedcontext.booking.app.dto.request.BookingPendingRequest;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingCountResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPendingResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.facade.BookingFacade;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.security.CustomUserDetails;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Booking", description = "예약 관련 API")
@Validated
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
    BookingPendingResponse response =
        bookingFacade.createBooking(user.getUserId(), request.performanceId(), request.seatId());

    return ApiResponse.onSuccess(SuccessStatus.CREATED, response);
  }

  @Operation(
      summary = "내 예매 내역 조회",
      description = "로그인한 사용자의 예매 내역을 조회합니다. 좌석 번호와 공연 상세 정보는 각 모듈 API에서 조회합니다.")
  @GetMapping("/me")
  public ResponseEntity<ApiResponse<List<BookingSummaryResponse>>> getMyBookings(
      @AuthenticationPrincipal CustomUserDetails user,
      @RequestParam(defaultValue = "CONFIRMED") BookingStatus status,
      @ModelAttribute OffsetPageRequest pageRequest) {
    Page<BookingSummaryResponse> response =
        bookingFacade.getMyBookings(user.getUserId(), status, pageRequest);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(summary = "내 예매 수 조회", description = "로그인한 사용자의 상태별 예매 수를 조회합니다.")
  @GetMapping("/me/count")
  public ResponseEntity<ApiResponse<BookingCountResponse>> countMyBookings(
      @AuthenticationPrincipal CustomUserDetails user,
      @RequestParam(defaultValue = "CONFIRMED") BookingStatus status) {
    BookingCountResponse response = bookingFacade.countMyBookings(user.getUserId(), status);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(
      summary = "내 예매 취소",
      description =
          """
          로그인한 사용자의 예매를 취소합니다. 예매 상태에 따라 처리가 갈립니다.

          - `PENDING` (결제 전): 즉시 취소(`CANCELED`)하고 선점 좌석의 반납을 요청합니다. 정상 경로에서는
            응답을 받은 시점에 좌석이 이미 `AVAILABLE`입니다. 다만 좌석 반납은 best-effort이며, 실패해도
            예매 취소는 성사됩니다(그때는 기존 선점 만료 5분과 60초 스케줄러가 좌석을 회수합니다).
            좌석 상태에 의존하는 화면은 응답을 신뢰하지 말고 좌석 조회를 다시 하십시오.
          - `CONFIRMED` (결제 완료): 환불을 요청합니다(`REFUNDING`). 좌석 반환과 예매 종결은 환불 성공 이후입니다.

          이미 입장을 완료한(입장권 사용됨) 예매는 취소할 수 없습니다(409 `BOOKING_409_006`). 착석한 좌석이
          재판매되는 것을 막기 위한 정책이며, 이때 예매는 확정 상태 그대로 유지됩니다.

          그 밖의 상태(EXPIRED·CANCELED·REFUNDED)는 409 `BOOKING_409_001`입니다.
          """)
  @DeleteMapping("/{bookingNumber}")
  public ResponseEntity<ApiResponse<Void>> cancelMyBooking(
      @AuthenticationPrincipal CustomUserDetails user, @PathVariable String bookingNumber) {
    bookingFacade.cancelMyBooking(user.getUserId(), bookingNumber);

    return ApiResponse.onSuccess(SuccessStatus.OK);
  }
}
