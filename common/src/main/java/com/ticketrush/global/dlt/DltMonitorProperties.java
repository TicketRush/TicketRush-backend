package com.ticketrush.global.dlt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DLT 모니터링 설정.
 *
 * <p>모든 {@code .DLT} 토픽을 한 곳에서 소비·저장하기 위해 <b>딱 한 서비스</b>(기본: booking-service)에서만 {@code
 * enabled=true}로 켠다. 나머지 서비스는 기본 비활성이라 {@link DeadLetterConsumer}가 생성되지 않는다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.dlt.monitor")
public class DltMonitorProperties {

  private boolean enabled = false;
}
