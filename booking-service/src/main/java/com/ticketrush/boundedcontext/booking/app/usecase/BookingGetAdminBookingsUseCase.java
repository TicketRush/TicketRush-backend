package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 전체 예매를 조회한다 (#561). 상태 하나로 좁히는 필터만 제공하며(#667) 검색·다중조건 필터는 이번 범위가 아니다.
 *
 * <p><b>{@code BookingSummaryResponse}로 감싸지 않고 엔티티를 반환한다.</b> 그 DTO에는 {@code createdAt}이 없는데 화면의
 * "예매 일시"가 바로 그 값이고, 환불 관리자 조회 2개와 공유 중이라 필드를 더하면 그쪽 응답까지 함께 바뀐다. {@code Booking}에는 지연 로딩 연관이 없어
 * 트랜잭션 밖 접근이 안전하다(단건 조회가 이미 같은 방식이다).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingGetAdminBookingsUseCase {

  private final BookingRepository bookingRepository;

  /**
   * 최신 예매부터 반환한다. {@code status}가 null이면 상태 무관 전체를 조회한다 — 파라미터가 없던 시절의 동작이 그대로 기본값이다.
   *
   * <p>정렬 키가 {@code createdAt}이 아니라 {@code id}인 것은 의도적이다. auto_increment id는 삽입 순서와 단조라 화면상 순서가
   * 같으면서 PK 인덱스를 그대로 타 filesort가 없다 — {@code created_at}에는 인덱스가 없어 전체 정렬이 매 요청 발생한다. 동시각 예매의
   * tie-break도 따라온다.
   *
   * <p><b>두 경로의 비용은 같지 않다.</b> 전체 조회는 PK 인덱스를 역순으로 훑어 정렬이 아예 없지만, {@code status}를 주면 {@code
   * idx_booking_status_updated_at}으로 행을 좁힌 뒤 <b>id 정렬이 새로 생긴다</b> — 그 인덱스의 리프 순서가 {@code
   * (booking_status, updated_at, id)}라 id DESC를 덮지 못한다. 정렬 대상이 해당 상태 몫으로 줄어드는 것이지, 없던 비용이 안 생기는 것이
   * 아니다. 자세한 근거와 인덱스 판단은 {@code BookingRepository.findByBookingStatus} 주석에 있다.
   */
  public Page<Booking> execute(BookingStatus status, OffsetPageRequest pageRequest) {
    PageRequest pageable =
        PageRequest.of(pageRequest.page(), pageRequest.size(), Sort.by(Sort.Order.desc("id")));

    return status == null
        ? bookingRepository.findAll(pageable)
        : bookingRepository.findByBookingStatus(status, pageable);
  }
}
