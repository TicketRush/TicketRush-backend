package com.ticketrush.global.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.global.json.JsonConverter;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

/**
 * {@link SlackNotifier} 단위 테스트.
 *
 * <p>{@link RestClient} 플루언트 체인을 Mockito로 모킹해 실제 HTTP/컨버터 초기화 없이 순수 단위 검증한다({@link SlackNotifier}의
 * RestClient 주입 생성자 사용).
 */
@ExtendWith(MockitoExtension.class)
class SlackNotifierTest {

  private static final String WEBHOOK_URL = "https://hooks.slack.test/services/webhook";

  @Mock private JsonConverter jsonConverter;
  @Mock private RestClient restClient;
  @Mock private RestClient.RequestBodyUriSpec requestBodyUriSpec;
  @Mock private RestClient.RequestBodySpec requestBodySpec;
  @Mock private RestClient.ResponseSpec responseSpec;

  private SlackNotifier slackNotifier;

  @BeforeEach
  void setUp() {
    SlackProperties properties = new SlackProperties();
    properties.setWebhookUrl(WEBHOOK_URL);

    slackNotifier = new SlackNotifier(properties, jsonConverter, restClient);
  }

  private void givenPostChain() {
    given(restClient.post()).willReturn(requestBodyUriSpec);
    given(requestBodyUriSpec.uri(WEBHOOK_URL)).willReturn(requestBodySpec);
    given(requestBodySpec.contentType(any())).willReturn(requestBodySpec);
    given(requestBodySpec.body(anyString())).willReturn(requestBodySpec);
    given(requestBodySpec.retrieve()).willReturn(responseSpec);
  }

  @Test
  @DisplayName("send: webhook URL로 직렬화한 JSON 페이로드를 POST한다")
  void send_posts_payload_to_webhook() {
    given(jsonConverter.serialize(any())).willReturn("{\"text\":\"hello\"}");
    givenPostChain();

    slackNotifier.send("제목", "본문", Map.of("eventId", "evt-1"));

    verify(requestBodyUriSpec).uri(WEBHOOK_URL);
    verify(requestBodySpec).body("{\"text\":\"hello\"}");
    verify(responseSpec).toBodilessEntity();
  }

  @Test
  @DisplayName("send: webhook 호출이 실패해도 예외를 삼키고 전파하지 않는다")
  void send_swallows_exception_on_failure() {
    given(jsonConverter.serialize(any())).willReturn("{\"text\":\"hello\"}");
    given(restClient.post()).willReturn(requestBodyUriSpec);
    given(requestBodyUriSpec.uri(WEBHOOK_URL)).willReturn(requestBodySpec);
    given(requestBodySpec.contentType(any())).willReturn(requestBodySpec);
    given(requestBodySpec.body(anyString())).willReturn(requestBodySpec);
    given(requestBodySpec.retrieve()).willThrow(new RuntimeException("slack down"));

    assertThatCode(() -> slackNotifier.send("제목", "본문", Map.of())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("NoOpNotifier: 아무 것도 하지 않고 예외 없이 반환한다")
  void noop_notifier_does_nothing() {
    NoOpNotifier noOpNotifier = new NoOpNotifier();

    assertThatCode(() -> noOpNotifier.send("제목", "본문", Map.of("k", "v")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("send: 제목/본문/메타데이터를 합친 text로 페이로드를 직렬화한다")
  void send_serializes_combined_text() {
    given(jsonConverter.serialize(any())).willReturn("{\"text\":\"hello\"}");
    givenPostChain();

    slackNotifier.send("제목", "본문", Map.of("eventId", "evt-1"));

    // text에 제목/본문/메타데이터가 모두 포함된 body가 직렬화 대상으로 넘어간다.
    verify(jsonConverter)
        .serialize(
            org.mockito.ArgumentMatchers.argThat(
                body -> {
                  @SuppressWarnings("unchecked")
                  Map<String, String> map = (Map<String, String>) body;
                  String text = map.get("text");
                  return text.contains("제목")
                      && text.contains("본문")
                      && text.contains("eventId: evt-1");
                }));
    verify(requestBodyUriSpec).uri(eq(WEBHOOK_URL));
  }

  @Test
  @DisplayName("생성자: webhook-url이 비어있으면 IllegalStateException으로 즉시 실패한다(#5)")
  void constructor_throws_when_webhook_url_is_blank() {
    SlackProperties emptyProps = new SlackProperties();
    // webhookUrl 미설정 → null

    assertThatThrownBy(() -> new SlackNotifier(emptyProps, jsonConverter, restClient))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("webhook-url");
  }
}
