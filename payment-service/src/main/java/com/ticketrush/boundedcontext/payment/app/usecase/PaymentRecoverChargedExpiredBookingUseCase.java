package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.usecase.PaymentRefundByBookingUseCase.RefundOutcome;
import com.ticketrush.boundedcontext.payment.domain.entity.ExpiredBooking;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
import com.ticketrush.boundedcontext.payment.domain.types.RefundTrigger;
import com.ticketrush.boundedcontext.payment.out.apiclient.BookingRestClient;
import com.ticketrush.boundedcontext.payment.out.apiclient.dto.BookingInfoResponse;
import com.ticketrush.boundedcontext.payment.out.repository.ExpiredBookingRepository;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.boundedcontext.payment.out.repository.RefundRepository;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.exception.BusinessException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 과금됐으나 예매가 만료된 건을 찾아 자동 환불한다 (#607).
 *
 * <p><b>왜 필요한가.</b> {@code PaymentConfirmedEvent} 발행이 유실되면 booking 은 PENDING 에 남고, 5 분 뒤 {@code
 * BookingExpirationScheduler} 가 EXPIRED 로 만든다. 그 결과가 <b>payment 는 COMPLETED(과금 완료), booking 은
 * EXPIRED, 티켓 미발급, 좌석 재판매, 환불 없음</b>이다. 이 상태는 어느 미해결 목록에도 잡히지 않는다 — {@code
 * Booking#recordRefundFailure} 가 EXPIRED·CANCELED 에서 아무것도 하지 않아 {@code refund_failed_at} 이 <b>구조적으로
 * 채워질 수 없기</b> 때문이다. 사용자 돈이 환불 없이 남는데 그것을 드러내는 축조차 없다.
 *
 * <p><b>왜 #574 방식을 복제하지 않는가.</b> 그쪽은 {@code EventReplayPublisher} 로 신호를 재발행해 수신 측 계약에 복구를 위임했다. 여기서
 * 같은 일을 하면 아무 일도 일어나지 않는다 — {@code PaymentConfirmedEvent} 를 다시 보내도 {@code Booking#confirm} 이
 * EXPIRED 에 대해 {@code BOOKING_EXPIRED} 로 거절하고, 그 예외는 영구 실패로 분류돼 그대로 ack 된다. 이미 좌석이 팔린 뒤라 예매를 되살릴 수도
 * 없다. 그래서 <b>복구의 방향이 반대다 — 예매를 살리는 게 아니라 과금을 되돌린다.</b> 재사용하는 것은 스케줄러 골격(배치 상한 + 커서 +
 * {@code @Transactional} 금지)뿐이다.
 *
 * <p><b>불변식: 이 UseCase 에 {@code @Transactional} 을 붙이면 안 된다.</b> 안에서 booking HTTP 조회와 PG 취소 왕복이
 * 일어나며, 트랜잭션 안에서 돌면 그 왕복 동안 DB 커넥션을 함께 문다. payment 가 PG 왕복을 트랜잭션 밖으로 뺀 것과 같은 이유다({@code
 * PaymentCancelUseCase} · {@link FailedRefundRecorder} 의 동일 불변식). 나아가 {@link FailedRefundRecorder}
 * 의 FAILED 이력이 독립 커밋되려면 호출자가 트랜잭션 밖이어야 한다는 전제가 여기에도 그대로 걸린다.
 *
 * <p><b>ShedLock 을 붙이지 않는다 — 다만 근거가 #574 와 다르다.</b> 그쪽은 "읽기와 발행뿐이라 전이할 상태가 없다"가 근거였지만 이 작업에는 <b>PG
 * 환불이라는 되돌릴 수 없는 부작용이 있다.</b> 그럼에도 락을 두지 않는 근거는 중복 실행이 이중 환불로 번지지 않게 막는 방어선 셋이 이미 있다는 것이다 — PG 멱등키가
 * {@code paymentId} 로 고정되고({@code REFUND-%07d}), {@code refund.payment_id} 에 unique 가 있으며, {@code
 * Payment#markCanceled} 가 COMPLETED 가 아니면 예외를 던진다. 아래 환불 이력 선검사는 이 셋에 얹는 PG 호출 억제일 뿐 정합성 근거가 아니다.
 * ShedLock 을 들이려면 payment 가 명시적으로 끈 Redis 오토컨피그(#425)를 되살려 이 서비스를 Redis 장애 범위(ADR 0008)에 새로 넣어야 한다.
 * 근거는 ADR 0015.
 *
 * <p><b>기본 비활성이다.</b> 첫 랩이 과거에 쌓인 사고 건을 <b>전량</b> 환불하므로, 잔량을 실측하고 켠다. CS 가 PG 콘솔에서 수동 환불한 건은
 * payment 가 COMPLETED 로 남아 있어 중복 취소를 시도하게 된다(Toss 가 이미 취소된 건을 거절하므로 이중 환급은 아니지만 FAILED 이력과 알람은
 * 남는다).
 */
@Slf4j
@Service
@ConditionalOnProperty(
    prefix = "app.charged-expired-recovery",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
public class PaymentRecoverChargedExpiredBookingUseCase {

  /**
   * 이 상태의 예매만 자동 환불 대상으로 본다.
   *
   * <p>CANCELED 를 포함하는 것은 의도적이다. 사용자가 만료 전에 예매를 취소했는데 확정 신호만 유실된 경우도 결과는 같다 — 과금이 남고 예매가 없다.
   */
  private static final Set<String> REFUNDABLE_BOOKING_STATUSES = Set.of("EXPIRED", "CANCELED");

  /**
   * booking-service {@code BookingStatus} 로 알려진 이름 전부.
   *
   * <p>허용 목록({@link #REFUNDABLE_BOOKING_STATUSES})과 <b>따로 두는 이유</b>는 "환불 대상이 아니다"와 "상대가 계약을 바꿨다"를
   * 갈라야 하기 때문이다. 상태는 외부 서비스가 주는 문자열이라 컴파일러가 지켜주지 못하는데, 두 경우를 한 태그로 접으면 booking 이 이름을 바꾼 배포 사고가 "예매가
   * 살아 있어서 건너뜀"이라는 정상 시계열에 통째로 묻힌다 — 그동안 사고 복구는 전면 정지한다. #572 가 {@code owner_unknown} 을 {@code
   * lookup_failed} 에서 분리한 것과 같은 판단이다.
   */
  private static final Set<String> KNOWN_BOOKING_STATUSES =
      Set.of("PENDING", "CONFIRMED", "CANCELED", "REFUNDING", "REFUNDED", "EXPIRED");

  /** 이미 환불이 성사돼 손댈 것이 없는 건. */
  private static final String SKIP_ALREADY_REFUNDED = "already_refunded";

  /**
   * 환불을 시도했으나 PG 가 거절해 FAILED 이력만 남은 건.
   *
   * <p>{@link #SKIP_ALREADY_REFUNDED} 와 <b>반드시 갈라야 한다.</b> 둘 다 "환불 이력이 있어 건너뛴다"이지만 뜻이 정반대다 — 이쪽은
   * <b>돈이 돌아가지 않은 채 남은 미해결 사고</b>이고, 예매가 EXPIRED 라 booking 의 관리자 재환불 게이트도 열리지 않는다. 한 태그로 접으면 그 건이
   * 정상 환불 시계열에 섞여 영영 보이지 않는다.
   */
  private static final String SKIP_REFUND_FAILED_HISTORY = "refund_failed_history";

  private static final String SKIP_GRACE = "grace";
  private static final String SKIP_BOOKING_LOOKUP_FAILED = "booking_lookup_failed";

  /** 예매가 아직 살아 있어(알려진 비허용 상태) 환불 대상이 아닌 건. */
  private static final String SKIP_BOOKING_ALIVE = "booking_alive";

  /** booking 이 알려지지 않은 상태 이름을 돌려준 건 — 정상 스킵이 아니라 계약 파기 신호다. */
  private static final String SKIP_BOOKING_STATUS_UNKNOWN = "booking_status_unknown";

  private static final String SKIP_BOOKING_NUMBER_UNKNOWN = "booking_number_unknown";

  private final ExpiredBookingRepository expiredBookingRepository;
  private final PaymentRepository paymentRepository;
  private final RefundRepository refundRepository;
  private final BookingRestClient bookingRestClient;
  private final PaymentRefundByBookingUseCase paymentRefundByBookingUseCase;
  private final MeterRegistry meterRegistry;

  /**
   * 이번 주기가 이어서 읽을 expired_booking_id 하한(배타).
   *
   * <p>커서가 없으면 만료 건수가 배치 상한을 넘는 순간 뒤쪽이 <b>영구히</b> 선택되지 않는다. {@code expired_booking} 은 해결돼도 행이 사라지지
   * 않아 대상 집합이 스스로 줄지 않기 때문이다 — #574 가 FAILED 이력에서 겪은 것과 같은 형태이되, 그쪽은 성공하면 COMPLETED 로 빠져나가기라도 했다.
   *
   * <p>스케줄러 스레드 풀이 2 라 주기마다 다른 스레드가 실행될 수 있어 {@code volatile} 로 가시성을 맞춘다. 재기동하면 0 부터 다시 도는데, 순환만
   * 보장되면 되므로 영속화하지 않는다.
   *
   * <p><b>단일 호출자 전제다.</b> {@code @Scheduled(fixedDelay)} 가 같은 메서드의 실행을 겹치지 않게 해 주는 덕에 이 필드의
   * read-modify-write 가 인터리브되지 않는다. 관리자 수동 트리거 같은 두 번째 호출자를 붙이면 두 실행이 커서를 서로 덮어써 구간을 통째로 건너뛰므로, 그때는
   * 이 전제부터 다시 세워야 한다.
   */
  private volatile long cursor = 0L;

  /** 한 후보를 처리한 결과. 랩당 환불 상한을 세려면 "판정에서 걸러짐"과 "시도했음"을 갈라야 한다. */
  private enum RecoverOutcome {
    /** 환불이 성사됐다. */
    REFUNDED,
    /**
     * 환불을 시도했으나 성사되지 않았다(거절·통신 실패·경합).
     *
     * <p>{@code PaymentRefundByBookingUseCase} 가 대상 결제를 찾지 못해 PG 를 <b>실제로 부르지 않고</b> 돌아온 경우 ({@code
     * ALREADY_SETTLED}·{@code REPUBLISHED})도 여기 들어간다. 예산을 보수적으로 — 즉 더 일찍 멈추는 쪽으로 — 쓰는 선택이다. 반대로 짜면
     * "무엇이 PG 를 실제로 불렀는가"를 이 바깥에서 판정해야 하는데, 그 판정이 틀리는 방향은 상한을 넘겨 호출하는 쪽이라 훨씬 무겁다.
     */
    ATTEMPTED,
    /** 판정에서 걸러져 환불을 시도하지 않았다. */
    SKIPPED
  }

  public void execute(int batchSize, int graceMinutes, int maxRefundsPerLap) {
    List<ExpiredBooking> targets =
        expiredBookingRepository.findByIdGreaterThanOrderByIdAsc(
            cursor, PageRequest.of(0, batchSize));
    if (targets.isEmpty()) {
      cursor = 0L;
      return;
    }

    Set<Long> chargedBookingIds = chargedBookingIds(targets);
    RefundHistory refundHistory = refundHistory(chargedBookingIds);
    LocalDateTime graceDeadline = LocalDateTime.now().minusMinutes(graceMinutes);

    int detected = 0;
    int recovered = 0;
    int refundAttempts = 0;
    boolean throttled = false;

    for (ExpiredBooking expired : targets) {
      // 랩당 환불 시도 상한. Toss 는 429 대신 연속 요청 제한(FORBIDDEN_CONSECUTIVE_REQUEST, 403)을 내고
      // 그 코드의 javadoc 이 "가장 나오기 쉬운 구간은 대량 보상 환불 버스트"라고 적고 있다. 거기 걸리면
      // #573 인라인 재시도까지 같은 이유로 소진돼 FAILED 이력이 남고, 그 건은 판정 1 에 걸려 자동 복구에서
      // 영구히 빠진다 — 되돌릴 수 없는 부작용을 다루는 경로에서 가장 나쁜 결말이다.
      //
      // 이 검사가 커서 전진보다 앞에 있어야 한다. 뒤에 두면 상한에 걸려 처리하지 못한 바로 그 건까지
      // 커서가 넘어가 다음 주기가 건너뛴다. 대가로 상한에 도달하면 만료 스캔 자체가 멈추지만(뒤쪽에
      // 과금되지 않은 정상 건이 있어도 이번 주기엔 보지 않는다), 다음 주기가 곧바로 이어받으므로
      // 굶는 건은 없다. 정상 운영에서는 후보가 희소해 상한에 닿지 않는다.
      if (refundAttempts >= maxRefundsPerLap) {
        throttled = true;
        break;
      }

      // 판정 결과와 무관하게 전진시킨다. #574 의 "연속 실패 시 커서를 두고 중단" 을 복제하면 안 된다 —
      // 거기서 연속 실패는 브로커 전면 장애라 남은 건도 같은 이유로 실패할 것이 확실했지만, 여기서 연속 실패는
      // 특정 건의 PG·booking 왕복 실패다. 커서를 두고 멈추면 선두의 실패한 몇 건이 뒤쪽 사고 건 전체를
      // 무기한 굶긴다 — 이 스케줄러가 없애려던 봉쇄가 위치만 바꿔 재현된다.
      cursor = expired.getId();

      Long bookingId = expired.getBookingId();

      if (!chargedBookingIds.contains(bookingId)) {
        // 만료됐지만 과금되지 않은 정상 건이다. 만료 대부분이 여기 해당하므로 카운터도 올리지 않는다.
        continue;
      }

      // 1) 이미 환불 이력이 있으면 손대지 않는다. 상태를 가려 FAILED 를 다시 집어들면 실패한 환불을 매 주기
      //    PG 에 되풀이해 던지게 되므로, 성사 여부와 무관하게 "이미 시도했다"로 본다. 다만 관측에서는 가른다.
      if (refundHistory.settled().contains(bookingId)) {
        countSkip(SKIP_ALREADY_REFUNDED);
        continue;
      }
      if (refundHistory.failedOnly().contains(bookingId)) {
        countSkip(SKIP_REFUND_FAILED_HISTORY);
        continue;
      }

      // 2) 만료 직후는 정상 처리가 진행 중일 수 있다. 확정 신호가 조금 늦게 도착하는 창을 환불로 덮지 않는다.
      if (expired.getExpiredAt().isAfter(graceDeadline)) {
        countSkip(SKIP_GRACE);
        continue;
      }

      // 여기까지 오면 로컬 근거만으로는 "과금됐는데 예매가 만료됐고 환불이 없는" 건이다.
      // 아래 booking 재확인에서 걸러지는 건까지 포함해 세는 이유는, 그 갈래들이야말로 자동 복구가 닿지
      // 못한 채 남는 사고이기 때문이다 — detected 와 recovered 의 차이가 곧 미복구 잔량이다.
      detected++;
      RecoverOutcome outcome = recover(bookingId);
      if (outcome != RecoverOutcome.SKIPPED) {
        refundAttempts++;
      }
      if (outcome == RecoverOutcome.REFUNDED) {
        recovered++;
      }
    }

    long scannedUpTo = cursor;
    if (!throttled && targets.size() < batchSize) {
      // 마지막 페이지까지 훑었으면 다음 주기가 다시 앞에서 시작하도록 되돌린다. 상한에 걸려 중단했다면
      // 아직 뒤쪽을 보지 못했으므로 되돌리지 않는다.
      cursor = 0L;
    }

    if (detected > 0) {
      Counter.builder(MetricNames.PAYMENT_CHARGED_EXPIRED_DETECTED)
          .register(meterRegistry)
          .increment(detected);
      // 커서를 되돌리기 전 값을 남긴다. 어디까지 훑고 발견했는지가 이 로그의 유일한 진단 가치다.
      log.warn(
          "확정 신호 유실로 과금이 남은 만료 예매를 발견했습니다. 발견={}건, 복구={}건, 마지막 확인={}",
          detected,
          recovered,
          scannedUpTo);
    }
    if (recovered > 0) {
      Counter.builder(MetricNames.PAYMENT_CHARGED_EXPIRED_RECOVERED)
          .register(meterRegistry)
          .increment(recovered);
    }
    if (throttled) {
      log.warn("랩당 환불 상한에 도달해 이번 주기를 조기 종료합니다. 환불 시도={}건, 마지막 확인={}", refundAttempts, scannedUpTo);
    }
  }

  /**
   * 후보 묶음 중 COMPLETED 결제가 있는 bookingId 집합을 한 번에 조회한다.
   *
   * <p>후보를 한 건씩 조회하면 배치 크기만큼 왕복이 늘고, 만료와 결제를 조인 한 방으로 합치면 매치가 희소해 조기 종료를 못 한 채 매 주기 풀스캔이 된다(ADR
   * 0014:76 의 함정과 같은 형태). 그래서 2 단계로 나눈다.
   */
  private Set<Long> chargedBookingIds(List<ExpiredBooking> targets) {
    List<Long> bookingIds = targets.stream().map(ExpiredBooking::getBookingId).toList();
    return paymentRepository
        .findByBookingIdInAndStatus(bookingIds, PaymentStatus.COMPLETED)
        .stream()
        .map(Payment::getBookingId)
        .collect(Collectors.toSet());
  }

  /**
   * 과금 후보들의 환불 이력을 <b>한 번의 질의로</b> 가져온다.
   *
   * <p>후보마다 {@code existsByBookingId} 를 부르면 안 된다. {@code refund} 에는 {@code booking_id} 인덱스가 없어(키는
   * PK · {@code UNIQUE(payment_id)} · {@code idx_refund_status} 뿐) 호출마다 풀스캔이고, 그 테이블은 성공한 환불까지 전부
   * 누적돼 시간이 갈수록 커진다. 후보가 가장 많은 순간이 하필 처음 켜는 첫 랩이다. {@code IN} 으로 묶으면 풀스캔이 랩당 1 회로 줄어, 인덱스를 새로 만들지
   * 않고도(= 수동 DDL 없이) 비용이 후보 수에 비례하지 않게 된다.
   */
  private RefundHistory refundHistory(Set<Long> chargedBookingIds) {
    if (chargedBookingIds.isEmpty()) {
      return new RefundHistory(Set.of(), Set.of());
    }

    List<Refund> refunds = refundRepository.findByBookingIdIn(chargedBookingIds);
    Set<Long> settled =
        refunds.stream()
            .filter(refund -> refund.getStatus() != RefundStatus.FAILED)
            .map(Refund::getBookingId)
            .collect(Collectors.toSet());
    Set<Long> failedOnly =
        refunds.stream()
            .filter(refund -> refund.getStatus() == RefundStatus.FAILED)
            .map(Refund::getBookingId)
            .filter(bookingId -> !settled.contains(bookingId))
            .collect(Collectors.toSet());
    return new RefundHistory(settled, failedOnly);
  }

  /**
   * 환불 이력의 두 갈래.
   *
   * @param settled 환불이 성사됐거나(COMPLETED) 진행 중인(PENDING) bookingId — 어느 쪽이든 손대지 않는다
   * @param failedOnly FAILED 이력만 있는 bookingId — 돈이 돌아가지 않은 미해결 사고다
   */
  private record RefundHistory(Set<Long> settled, Set<Long> failedOnly) {}

  /**
   * 후보 한 건을 booking 에 재확인하고, 조건이 맞으면 자동 환불한다.
   *
   * <p><b>판정 순서 자체가 계약이다.</b> 로컬 근거(만료 테이블)는 이벤트로 채워진 사본이라 최신이 아닐 수 있고, 되돌릴 수 없는 부작용(PG 환불) 앞에서는
   * 원본을 다시 본다. 조회하지 못하면 <b>환불하지 않는다(fail-closed)</b> — 잘못 환불하는 것이 환불이 늦는 것보다 무겁다.
   */
  private RecoverOutcome recover(Long bookingId) {
    BookingInfoResponse booking = lookupBooking(bookingId);
    if (booking == null) {
      return RecoverOutcome.SKIPPED;
    }

    String bookingStatus = booking.bookingStatus();
    if (bookingStatus == null) {
      // Set.of(...).contains(null) 은 NPE 를 던지므로 반드시 먼저 갈라야 한다(#490 에서 밟은 함정).
      // 200 을 받았어도 상태를 모르면 판정에 도달하지 못한 것이라 조회 실패와 같이 다룬다.
      countSkip(SKIP_BOOKING_LOOKUP_FAILED);
      log.warn("만료 예매 재확인 응답에 상태가 없어 자동 환불을 보류합니다. bookingId={}", bookingId);
      return RecoverOutcome.SKIPPED;
    }

    // 4) 예매가 살아 있으면 환불 대상이 아니다. 알려지지 않은 상태는 정상 스킵과 갈라 CRITICAL 로 올린다 —
    //    상대가 상태 이름을 바꾸면 전건이 이 갈래로 떨어지며 사고 복구가 조용히 전면 정지하기 때문이다.
    if (!REFUNDABLE_BOOKING_STATUSES.contains(bookingStatus)) {
      if (KNOWN_BOOKING_STATUSES.contains(bookingStatus)) {
        countSkip(SKIP_BOOKING_ALIVE);
        log.warn(
            "만료로 기록된 예매가 실제로는 종결되지 않아 자동 환불을 보류합니다. bookingId={}, status={}",
            bookingId,
            bookingStatus);
      } else {
        countSkip(SKIP_BOOKING_STATUS_UNKNOWN);
        log.error(
            "[CRITICAL] booking 이 알 수 없는 예매 상태를 반환했습니다! 자동 복구가 멈춥니다. bookingId={}, status={}",
            bookingId,
            bookingStatus);
      }
      return RecoverOutcome.SKIPPED;
    }

    return refund(bookingId, booking.bookingNumber());
  }

  /**
   * booking 원본을 재확인한다. 판정에 쓸 수 없는 응답은 전부 null 로 접는다(fail-closed).
   *
   * <p>{@link BusinessException} 밖의 예외까지 잡는 이유는, 그것이 새어 나가면 이번 주기의 <b>남은 후보 전체가 처리되지 않고 발견·복구 카운터와
   * 경고 로그도 통째로 실행되지 않기</b> 때문이다. 예를 들어 내부 API 토큰이 주입되지 않은 채 켜면 첫 후보에서 예외가 나는데, 그러면 "사고가 있었다"는 유일한
   * 관측 축이 하필 그 장애 구간에서 침묵한다.
   */
  private BookingInfoResponse lookupBooking(Long bookingId) {
    try {
      // 3) booking 원본 재확인. 404·503 모두 여기로 떨어지며, 어느 쪽이든 환불하지 않는다.
      return bookingRestClient.getBooking(bookingId);
    } catch (BusinessException e) {
      countSkip(SKIP_BOOKING_LOOKUP_FAILED);
      log.warn(
          "만료 예매 재확인에 실패해 자동 환불을 보류합니다. bookingId={}, code={}",
          bookingId,
          e.getErrorStatus().getCode());
      return null;
    } catch (Exception e) {
      countSkip(SKIP_BOOKING_LOOKUP_FAILED);
      log.error("만료 예매 재확인 중 예기치 못한 오류가 발생했습니다. bookingId={}", bookingId, e);
      return null;
    }
  }

  /**
   * 자동 환불을 실행한다. 한 건의 실패가 나머지를 멈추지 않도록 예외를 여기서 모두 막는다.
   *
   * <p><b>예매번호 가드가 이 메서드 안에 있는 것이 중요하다.</b> 값 없이 환불하면 {@code PaymentCanceledEvent} 가 {@code
   * bookingNumber=null} 로 나가고, seat 의 좌석 소유 교차검증(ABA 방지)이 통째로 꺼져 그 사이 다른 사용자에게 팔린 SOLD 좌석을 반환할 수
   * 있다(#608). 검증을 호출부에 두면 "호출처가 하나"라는 사실에만 안전이 걸리는데, {@code bookingId}·{@code bookingNumber} 가 각각
   * {@code Long}·{@code String} 이라 인자를 잘못 넘겨도 컴파일이 통과한다 — {@link RefundTrigger} 를 열거형으로 뽑은 이유와 같은
   * 위험이 남는 마지막 자리라 <b>부작용 직전</b>에 붙여 둔다.
   */
  private RecoverOutcome refund(Long bookingId, String bookingNumber) {
    // 5) 예매번호가 없으면 환불하지 않는다. 배포 순서가 역전돼 booking 이 아직 이 필드를 내려주지 않을 때도
    //    같은 경로로 null 이 되므로, 이 가드가 그 구간의 안전장치를 겸한다.
    if (bookingNumber == null || bookingNumber.isBlank()) {
      countSkip(SKIP_BOOKING_NUMBER_UNKNOWN);
      log.error(
          "[CRITICAL] 예매번호를 얻지 못해 자동 환불을 중단했습니다! 과금이 남아 수동 처리가 필요합니다. bookingId={}", bookingId);
      return RecoverOutcome.SKIPPED;
    }

    try {
      // 6) 자동 환불 실행.
      RefundOutcome outcome =
          paymentRefundByBookingUseCase.execute(
              bookingId, bookingNumber, RefundTrigger.CONFIRM_SIGNAL_LOST);

      countRefund(outcome);
      logOutcome(outcome, bookingId);
      return outcome == RefundOutcome.REFUNDED ? RecoverOutcome.REFUNDED : RecoverOutcome.ATTEMPTED;
    } catch (DataIntegrityViolationException e) {
      // 동시 환불이 unique 제약(#296)에 막힌 경우 = 이미 다른 경로에서 환불이 확정됨. 멱등 정상이다.
      log.info("동시 중복 자동 환불(unique 경합). bookingId={}", bookingId);
      return RecoverOutcome.ATTEMPTED;
    } catch (BusinessException e) {
      handleRefundFailure(e, bookingId);
      return RecoverOutcome.ATTEMPTED;
    } catch (Exception e) {
      // 인프라 일시 실패. 다음 랩이 같은 건을 다시 집어든다(환불 이력이 남지 않아 대상에 그대로 있다).
      log.error("자동 환불 중 일시적 오류가 발생했습니다. 다음 주기에 다시 시도합니다. bookingId={}", bookingId, e);
      return RecoverOutcome.ATTEMPTED;
    }
  }

  private void logOutcome(RefundOutcome outcome, Long bookingId) {
    switch (outcome) {
      case REFUNDED -> log.info("확정 신호 유실 건을 자동 환불했습니다. bookingId={}", bookingId);
      case REPUBLISHED ->
          log.info("이미 환불된 건이라 PaymentCanceledEvent 를 재발행(self-heal). bookingId={}", bookingId);
      // 후보를 뽑은 뒤 환불을 실행하기까지 사이에 다른 경로(사용자 취소 등)가 정리했을 수 있다. 신호를 받아
      // 처리하는 SeatConfirmFailedEventListener 에서는 같은 값이 "과금됐다"는 전제의 붕괴를 뜻해 CRITICAL
      // 이지만, 대조로 후보를 찾는 이 경로에서는 경합의 정상적인 귀결이다 — 심각도 해석이 다르다.
      case ALREADY_SETTLED -> log.info("자동 환불 대상 결제가 이미 정리되었습니다(경합). bookingId={}", bookingId);
      default -> throw new IllegalStateException("처리되지 않은 RefundOutcome: " + outcome);
    }
  }

  /**
   * PG 거절(결정적)과 통신 실패를 갈라 로그 심각도를 정한다.
   *
   * <p><b>{@code RefundFailedEvent} 를 발행하지 않는다.</b> 두 이벤트 리스너({@code
   * SeatConfirmFailedEventListener} · {@code RefundRequestedEventListener})는 거절 시 그것을 발행해 booking 의
   * {@code refundFailedAt} 을 채우고 관리자 재환불 API 를 연다. 그러나 이 경로의 대상은 EXPIRED·CANCELED 예매라 {@code
   * Booking#recordRefundFailure} 가 아무것도 하지 않는다 — 발행해도 게이트는 열리지 않고, 열렸다는 착각만 남는다. 그래서 이 건의 유일한 창구는
   * 아래 CRITICAL 로그와 {@code charged_expired.skipped{reason=refund_failed_history}} 카운터다. 전용 관리자 조회
   * API 는 후속 이슈다(ADR 0015).
   */
  private void handleRefundFailure(BusinessException e, Long bookingId) {
    if (!FailedRefundRecorder.isDeterministicRejection(e)) {
      log.warn(
          "자동 환불 중 재시도 가능한 실패(통신 오류 등). 다음 주기에 다시 시도합니다. bookingId={}, code={}",
          bookingId,
          e.getErrorStatus().getCode());
      return;
    }

    // 과금된 돈이 돌아가지 않은 채 남는다. 자동 복구가 끝난 지점이므로 수동 개입 대상으로 올린다.
    // FAILED 환불 이력은 PaymentRefundByBookingUseCase 안에서 이미 남았고, 그 이력 때문에 다음 주기부터는
    // refund_failed_history 로 걸러진다 — 같은 건을 PG 에 무한히 되던지지 않는다.
    log.error(
        "[CRITICAL] 확정 신호 유실 건의 자동 환불을 PG 가 거절했습니다! 과금이 남아 수동 처리가 필요합니다. bookingId={}",
        bookingId,
        e);

    Counter.builder(MetricNames.PAYMENT_REFUND_FAILED)
        .tag(MetricNames.TAG_TRIGGER, RefundTrigger.CONFIRM_SIGNAL_LOST.tag())
        .register(meterRegistry)
        .increment();
  }

  private void countRefund(RefundOutcome outcome) {
    Counter.builder(MetricNames.PAYMENT_REFUND)
        .tag(MetricNames.TAG_OUTCOME, outcome.name().toLowerCase(Locale.ROOT))
        .tag(MetricNames.TAG_TRIGGER, RefundTrigger.CONFIRM_SIGNAL_LOST.tag())
        .register(meterRegistry)
        .increment();
  }

  private void countSkip(String reason) {
    Counter.builder(MetricNames.PAYMENT_CHARGED_EXPIRED_SKIPPED)
        .tag(MetricNames.TAG_REASON, reason)
        .register(meterRegistry)
        .increment();
  }
}
