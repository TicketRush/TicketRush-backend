package com.ticketrush.global.event;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.EnumSet;
import java.util.Set;

/**
 * Kafka 컨슈머의 예외 처리 표준 정책(#269).
 *
 * <p>일시(transient/인프라) vs 영구(permanent/비즈니스) 예외를 구분해, 리스너가 ack(커밋) 할지 re-throw(재시도→DLT 위임) 할지 결정하도록
 * 돕는다. 대원칙은 fail-safe = 메시지 보존이다. 애매하면 transient로 간주해 re-throw 하여 기존 재시도→DLT 파이프라인으로 보존한다.
 *
 * <p>2계층 분류의 리스너 레벨 판정을 담당한다. 컨테이너 레벨(재시도 횟수 vs 즉시 DLT)은 {@code KafkaConfig}의 not-retryable 목록이
 * 담당하며 상보적으로 동작한다. 자세한 표준은 {@code docs/kafka-event-guide.md} 참고.
 *
 * <p><b>주의</b>: 크로스서비스 HTTP 호출(예: RestClient) 실패는 {@code BusinessException}이 아니라 {@code
 * RestClientResponseException}으로 도착하므로 이 헬퍼로 분류하지 않는다. 그런 호출은 HTTP 상태코드 기반으로 직접 분기해야 한다.
 */
public final class KafkaConsumerErrorPolicy {

  /** 자기참조 cause 체인으로 인한 무한 루프를 막는 순회 깊이 상한. */
  private static final int MAX_CAUSE_DEPTH = 20;

  /**
   * 재수신·순서로 자연스럽게 발생하는 "정상 상태충돌"로 취급할 {@link ErrorStatus} 화이트리스트.
   *
   * <p>모든 409를 정상으로 보면 결제는 됐으나 확정 불가 같은 정합성 위반({@code BOOKING_SEAT_MISMATCH}, {@code
   * BOOKING_EXPIRED}, {@code BOOKING_CONFIRM_NOT_ALLOWED} 등)까지 WARN으로 묻히므로, 재처리 시 결과가 동일한 멱등 상태만
   * 명시적으로 열거한다. 여기 없는 영구 실패는 {@code [CRITICAL]}로 남긴다.
   */
  private static final Set<ErrorStatus> EXPECTED_CONFLICTS =
      EnumSet.of(
          ErrorStatus.SEAT_NOT_AVAILABLE,
          ErrorStatus.SEAT_ALREADY_LOCKED,
          ErrorStatus.PAYMENT_ALREADY_COMPLETED,
          ErrorStatus.TICKET_ALREADY_USED,
          ErrorStatus.TICKET_NOT_USABLE,
          ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED);

  private KafkaConsumerErrorPolicy() {}

  /**
   * 재시도해도 결과가 바뀌지 않는 영구(비즈니스/결정적) 실패이면 {@code true}.
   *
   * <p>cause 체인에 {@link BusinessException}이 있으면 영구로 본다. 비즈니스 규칙 위반(SEAT_NOT_AVAILABLE 등)과 결정적
   * 실패(payload 역직렬화 {@code json.DeserializationException} 등)가 모두 여기 해당한다. 인프라
   * 실패(DataAccess/Redis/네트워크)는 {@code BusinessException}이 아니므로 일시(re-throw 대상)로 분류된다.
   */
  public static boolean isPermanent(Throwable e) {
    return findBusinessException(e) != null;
  }

  /**
   * 재수신·순서 등으로 자연스럽게 발생하는 "정상 상태충돌"({@link #EXPECTED_CONFLICTS})이면 {@code true}.
   *
   * <p>영구 실패이지만 재처리해도 결과가 같은 멱등 상태충돌이므로, 리스너는 {@code [CRITICAL]} 대신 info/warn 수준으로 로그하고 ack 한다.
   * {@code [CRITICAL]}은 화이트리스트에 없는 예상 밖 영구 실패(정합성 위반 등)에만 남겨 알림 피로를 줄인다.
   */
  public static boolean isExpectedConflict(Throwable e) {
    BusinessException businessException = findBusinessException(e);
    return businessException != null
        && EXPECTED_CONFLICTS.contains(businessException.getErrorStatus());
  }

  private static BusinessException findBusinessException(Throwable e) {
    Throwable current = e;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (current instanceof BusinessException businessException) {
        return businessException;
      }
      current = current.getCause();
    }
    return null;
  }
}
