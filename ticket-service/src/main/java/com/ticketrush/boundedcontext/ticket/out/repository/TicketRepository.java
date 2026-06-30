package com.ticketrush.boundedcontext.ticket.out.repository;

import com.ticketrush.boundedcontext.ticket.domain.entity.Ticket;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

  boolean existsByBookingId(Long bookingId);

  Optional<Ticket> findByBookingId(Long bookingId);

  /**
   * 입장권을 UNUSED -> USED로 1회만 전이시키는 조건부 UPDATE. {@code AND ticketStatus = :unused}가 동시성 가드 역할을 하여,
   * 동일 QR이 동시에 여러 번 스캔돼도 단 1건만 1행을 갱신(영향행수 1)하고 나머지는 0행이 되어 중복 입장을 막는다.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE Ticket t SET t.ticketStatus = :used, t.usedAt = :now "
          + "WHERE t.id = :ticketId AND t.ticketStatus = :unused")
  int markUsedById(
      @Param("ticketId") Long ticketId,
      @Param("now") LocalDateTime now,
      @Param("used") TicketStatus used,
      @Param("unused") TicketStatus unused);

  /**
   * 입장권을 UNUSED -> CANCELED로 전이시키는 조건부 UPDATE. 결제 취소(환불) 이벤트는 bookingId만 전달하므로 bookingId 기준으로 갱신한다.
   * {@code AND ticketStatus = :unused}가 가드 역할을 하여, 이미 USED(입장 완료)인 입장권은 0행이 되어 덮어쓰지 않고, 동일 취소 이벤트를
   * 중복 수신해도 (이미 CANCELED) 0행이 되어 멱등하게 동작한다.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE Ticket t SET t.ticketStatus = :canceled "
          + "WHERE t.bookingId = :bookingId AND t.ticketStatus = :unused")
  int markCanceledByBookingId(
      @Param("bookingId") Long bookingId,
      @Param("canceled") TicketStatus canceled,
      @Param("unused") TicketStatus unused);
}
