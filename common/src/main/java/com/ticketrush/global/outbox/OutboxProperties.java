package com.ticketrush.global.outbox;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Outbox relay 설정.
 *
 * <p>여러 서비스가 같은 outbox 테이블을 공유하므로, 각 서비스는 {@link #aggregateTypes}에 자기 소유 애그리거트 타입만 지정해 자신의 이벤트만
 * 발행한다(예: booking → {@code ["Booking"]}). 값은 {@code OutboxEventPublisher}가 저장하는 aggregateType
 * 표기(대문자 시작)와 일치해야 한다. 비어 있으면 relay는 아무 것도 발행하지 않는다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.outbox")
public class OutboxProperties {

  private List<String> aggregateTypes = new ArrayList<>();
  private int batchSize = 100;
  private int maxRetries = 3; // 이 횟수만큼 실패하면 DEAD로 전환해 자동 재발행을 멈춘다.
  private long retentionHours = 72; // SENT row를 이 시간 이후 retention 배치가 삭제한다.
}
