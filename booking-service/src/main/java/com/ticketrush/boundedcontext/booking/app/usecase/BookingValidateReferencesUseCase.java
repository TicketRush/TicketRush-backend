package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.out.repository.BookingReferenceReader;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingValidateReferencesUseCase {

  /**
   * 예매를 받을 수 있는 유일한 공연 상태 (#671).
   *
   * <p>{@code PerformanceStatus}가 performance-service 소유라 문자열로 비교한다 — 근거는 {@link
   * BookingReferenceReader#findPerformanceStatus} 참고.
   */
  private static final String ON_SALE = "ON_SALE";

  private final BookingReferenceReader bookingReferenceReader;

  public void execute(Long userId, Long performanceId, Long seatId) {
    if (!bookingReferenceReader.existsUserById(userId)) {
      throw new BusinessException(ErrorStatus.USER_NOT_FOUND);
    }

    validatePerformanceOnSale(performanceId);

    if (!bookingReferenceReader.existsSeatByIdAndPerformanceId(seatId, performanceId)) {
      throw new BusinessException(ErrorStatus.SEAT_NOT_FOUND);
    }
  }

  /**
   * 오픈 전 공연의 예매를 차단한다 (#671).
   *
   * <p>이 제한은 원래 프론트의 예매 버튼 활성 조건에만 있어 {@code POST /api/v1/booking}을 직접 호출하면 {@code UPCOMING} 공연도
   * 예매가 생성됐다. 인가 우회가 아니라 비즈니스 규칙 우회지만 결제로 이어지는 경로이므로 신뢰 경계인 서버에서 검증한다 — #668(환불 D-7)과 같은 유형이다.
   *
   * <p><b>공연 상태를 공유 스키마에서 직접 읽는다.</b> performance-service의 {@code
   * /api/v1/internal/performance/{id}/validate}를 부르는 길도 있지만, 그러면 예매 생성 <b>전원</b>에 원격 왕복이 붙어 다운스트림
   * 지연이 booking의 커넥션 풀을 물 수 있다({@code BookingValidateRefundDeadlineUseCase} Javadoc이 취소 경로에서 경고한 것과
   * 같은 위험이 핫패스에 생긴다). 이 UseCase는 같은 트랜잭션에서 {@code performance} 테이블을 이미 읽고 있었고(공연 존재 확인), 공유 DB는 ADR
   * 0003의 결정이다.
   *
   * <p><b>fail-closed는 구조로 성립한다.</b> 상태를 읽지 못하면 조회 자체가 실패해 요청이 끝나고, {@code ON_SALE}이 아닌 모든 값(알 수 없는
   * 문자열 포함)은 차단된다. 통과는 "{@code ON_SALE}을 확인했을 때"뿐이다.
   *
   * <p>상태 전환은 performance-service의 스케줄러가 맡으므로, 오픈 시각 정각과 이 판정 사이에 그 주기만큼의 지연이 있다.
   */
  private void validatePerformanceOnSale(Long performanceId) {
    String performanceStatus =
        bookingReferenceReader
            .findPerformanceStatus(performanceId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND));

    if (!ON_SALE.equals(performanceStatus)) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_NOT_ON_SALE);
    }
  }
}
