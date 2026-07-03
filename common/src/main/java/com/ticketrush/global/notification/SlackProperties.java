package com.ticketrush.global.notification;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Slack incoming-webhook 알림 설정.
 *
 * <p>{@code enabled=true}일 때만 {@link SlackNotifier}가 활성화된다. webhook-url은 외부 엔드포인트이므로 환경변수로 주입하고,
 * connect/read 타임아웃으로 알림 호출이 호출자 흐름을 오래 붙잡지 않게 제한한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.slack")
public class SlackProperties {

  private boolean enabled = false;
  private String webhookUrl;
  private long connectTimeoutMs = 3000;
  private long readTimeoutMs = 5000;
}
