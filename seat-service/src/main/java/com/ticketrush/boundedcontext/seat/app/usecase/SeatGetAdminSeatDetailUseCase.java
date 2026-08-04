package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatAdminSeatDetailResponse;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자가 좌석 1건의 상태와 선점 정보를 조회한다 (#562). */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SeatGetAdminSeatDetailUseCase {

  private final SeatRepository seatRepository;

  public SeatAdminSeatDetailResponse execute(Long performanceId, Long seatId) {
    return seatRepository
        .findByIdAndPerformanceId(seatId, performanceId)
        .map(seat -> SeatAdminSeatDetailResponse.of(seat, LocalDateTime.now()))
        .orElseThrow(() -> new BusinessException(ErrorStatus.SEAT_NOT_FOUND));
  }
}
