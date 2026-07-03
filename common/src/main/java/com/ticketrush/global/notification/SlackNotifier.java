package com.ticketrush.global.notification;

import com.ticketrush.global.config.RestClientFactorySupport;
import com.ticketrush.global.json.JsonConverter;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Slack incoming-webhook으로 운영 알림을 발송하는 {@link Notifier} 구현체.
 *
 * <p>{@code app.slack.enabled=true}일 때만 활성화된다. {@code webhook-url}이 비어있으면 기동 즉시 {@link
 * IllegalStateException}으로 실패한다(#5 — 조용한 실패 방지). 웹훅은 외부 엔드포인트이므로 {@link RestClientFactorySupport}로
 * connect/read 타임아웃을 건 자체 {@link RestClient}를 생성해 호출한다.
 *
 * <p>알림은 부가 기능이므로 <b>호출/네트워크 실패는 예외로 전파하지 않고 {@code log.warn}으로만 남긴다</b>. 알림 실패가 DLT 저장이나 Outbox 상태
 * 전이 같은 본 흐름을 깨서는 안 된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.slack", name = "enabled", havingValue = "true")
public class SlackNotifier implements Notifier {

  private final SlackProperties slackProperties;
  private final JsonConverter jsonConverter;
  private final RestClient restClient;

  @Autowired
  public SlackNotifier(SlackProperties slackProperties, JsonConverter jsonConverter) {
    this(slackProperties, jsonConverter, buildRestClient(slackProperties));
  }

  /** RestClient를 주입해 단위 테스트가 실제 HTTP/컨버터 초기화를 우회할 수 있게 하는 생성자. */
  SlackNotifier(
      SlackProperties slackProperties, JsonConverter jsonConverter, RestClient restClient) {
    if (!StringUtils.hasText(slackProperties.getWebhookUrl())) {
      throw new IllegalStateException(
          "app.slack.enabled=true 인데 app.slack.webhook-url 이 비어있습니다. "
              + "SLACK_WEBHOOK_URL 환경변수를 설정하세요.");
    }
    this.slackProperties = slackProperties;
    this.jsonConverter = jsonConverter;
    this.restClient = restClient;
  }

  private static RestClient buildRestClient(SlackProperties slackProperties) {
    return RestClient.builder()
        .requestFactory(
            RestClientFactorySupport.withTimeouts(
                slackProperties.getConnectTimeoutMs(), slackProperties.getReadTimeoutMs()))
        .build();
  }

  @Override
  public void send(String title, String message, Map<String, String> metadata) {
    try {
      String payload = jsonConverter.serialize(Map.of("text", buildText(title, message, metadata)));
      restClient
          .post()
          .uri(slackProperties.getWebhookUrl())
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      // 알림 실패는 본 흐름(저장/상태 전이)을 깨지 않도록 삼키고 로그만 남긴다.
      log.warn("[SLACK] 알림 전송 실패. title={}, error={}", title, e.getMessage());
    }
  }

  /** 제목/본문/메타데이터를 사람이 읽기 좋은 단일 텍스트로 합친다. */
  private String buildText(String title, String message, Map<String, String> metadata) {
    StringBuilder sb = new StringBuilder();
    sb.append(title);
    if (message != null && !message.isBlank()) {
      sb.append('\n').append(message);
    }
    if (metadata != null) {
      metadata.forEach((k, v) -> sb.append('\n').append(k).append(": ").append(v));
    }
    return sb.toString();
  }
}
