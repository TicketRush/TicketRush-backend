package com.ticketrush.boundedcontext.payment.domain.entity;

import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * booking-service의 BookingExpiredEvent로 수신한 만료 bookingId를 payment-service 로컬에 영속화한다. (#224)
 * 만료된 booking에 대한 결제 confirm 요청을 차단하는 방어선이며, bookingId unique 제약으로 중복 수신을 멱등 처리한다.
 */
@Entity
@Table(name = "expired_booking")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "id", column = @Column(name = "expired_booking_id"))
public class ExpiredBooking extends AutoIdBaseEntity {

  @Column(nullable = false, unique = true)
  private Long bookingId;

  @Column(nullable = false)
  private LocalDateTime expiredAt; // booking이 EXPIRED로 전이된 시점

  private ExpiredBooking(Long bookingId, LocalDateTime expiredAt) {
    this.bookingId = Objects.requireNonNull(bookingId, "bookingId는 null일 수 없습니다.");
    this.expiredAt = Objects.requireNonNull(expiredAt, "expiredAt은 null일 수 없습니다.");
  }

  public static ExpiredBooking of(Long bookingId, LocalDateTime expiredAt) {
    return new ExpiredBooking(bookingId, expiredAt);
  }
}
