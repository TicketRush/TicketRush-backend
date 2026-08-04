package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 전체 예매를 상태 무관으로 조회한다 (#561). 검색·다중조건 필터는 이번 범위가 아니라 페이징만 제공한다.
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
   * 최신 예매부터 반환한다.
   *
   * <p>정렬 키가 {@code createdAt}이 아니라 {@code id}인 것은 의도적이다. auto_increment id는 삽입 순서와 단조라 화면상 순서가
   * 같으면서 PK 인덱스를 그대로 타 filesort가 없다 — {@code created_at}에는 인덱스가 없어 전체 정렬이 매 요청 발생한다. 동시각 예매의
   * tie-break도 따라온다.
   */
  public Page<Booking> execute(OffsetPageRequest pageRequest) {
    return bookingRepository.findAll(
        PageRequest.of(pageRequest.page(), pageRequest.size(), Sort.by(Sort.Order.desc("id"))));
  }
}
