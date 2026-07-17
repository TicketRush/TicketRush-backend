package com.ticketrush.global.notification;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link SlackNotifier}가 비활성일 때({@code app.slack.enabled=false} 또는 미설정) 사용하는 no-op 폴백 {@link
 * Notifier}.
 *
 * <p>주입 측이 항상 non-null {@code Notifier}를 확보하도록 보장한다(빈 없음/NPE 회피). 실제 전송은 하지 않고 {@code debug} 로그만
 * 남긴다.
 *
 * <p>등록은 {@link NotifierConfig}의 {@code @Bean} + {@code @ConditionalOnProperty(prefix="app.slack",
 * name="enabled", havingValue="false", matchIfMissing=true)}로 한다. {@link SlackNotifier}는 {@code
 * havingValue="true"}이므로 두 빈이 동시에 등록되는 상황이 발생하지 않는다(순서 의존 제거, #4).
 */
@Slf4j
public class NoOpNotifier implements Notifier {

  @Override
  public void send(String title, String message, Map<String, String> metadata) {
    log.debug(
        "[NOOP-NOTIFIER] 알림 비활성. title={}, message={}, metadata={}", title, message, metadata);
  }
}
