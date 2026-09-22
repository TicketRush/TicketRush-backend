package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.out.repository.BookingReferenceReader;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.types.PerformanceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingValidateReferencesUseCase {

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
   * 같은 위험이 핫패스에 생긴다). 이 UseCase는 같은 트랜잭션에서 {@code performance} 테이블을 이미 읽고 있었으므로(공연 존재 확인) 읽는 테이블도
   * 왕복 수도 늘지 않는다.
   *
   * <p><b>ADR 0003이 이 읽기를 명시적으로 허용한다.</b> 그 ADR은 원래 "한 서비스는 다른 도메인의 테이블을 직접 조회·변경하지 않는다"였고, 이 경로는 그
   * 규율과 충돌했다 — 공유 DB를 유지하기로 한 결정이 직접 조회를 허락한 것은 아니었기 때문이다. #671에서 그 조항에 <b>읽기 전용 참조 검증</b> 예외를
   * 추가했다: 요청 처리 경로에서 다른 도메인의 <b>존재·상태만</b> 확인하는 읽기는 {@code out/repository}의 전용 리더를 통해 허용하고,
   * 조인·연관관계·쓰기는 여전히 금지한다. 근거는 이 경로가 핫패스이고, 같은 판정을 원격 호출로 바꾸면 다운스트림 지연이 예매 전체를 마비시킬 수 있다는 것이다.
   *
   * <p><b>읽기 실패는 차단으로 수렴한다.</b> 상태를 읽지 못하면 조회 자체가 실패해 요청이 끝나고, {@link PerformanceStatus#ON_SALE}이
   * 아닌 모든 값은 막힌다. 삭제된 공연도 리더가 {@code deleted_at}을 함께 보므로 빈 값이 돼 막힌다. 컬럼에 enum 에 없는 값이 있으면 리더의
   * {@code valueOf}가 던져 역시 차단된다.
   *
   * <p><b>남는 창은 두 방향이다.</b> 상태 전환은 performance-service의 스케줄러가 맡으므로 오픈 시각 정각과 이 판정 사이에 그 주기만큼 늦게
   * 열린다. 반대로 이 검증과 {@code BookingCreateUseCase}의 생성 트랜잭션은 서로 다른 트랜잭션이라, 그 사이에 {@code ON_SALE →
   * CLOSED/CANCELED} 전이가 끼면 닫힌 공연의 예매가 생성된다. 기존 존재 확인도 같은 구조였고 창이 수 ms라 분산 락은 도입하지 않았다 — #668의 환불
   * 가드가 같은 창을 남긴 것과 같은 판단이다.
   */
  private void validatePerformanceOnSale(Long performanceId) {
    PerformanceStatus performanceStatus =
        bookingReferenceReader
            .findPerformanceStatus(performanceId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND));

    if (performanceStatus != PerformanceStatus.ON_SALE) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_NOT_ON_SALE);
    }
  }
}
