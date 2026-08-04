package com.ticketrush.boundedcontext.booking.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ticketrush.boundedcontext.booking.out.apiclient.dto.UserSummaryInfoResponse;
import com.ticketrush.global.config.CustomSecurityProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 이 클라이언트는 관리자 목록 조회 전체를 죽이지 않기 위해 모든 실패를 흡수한다(#560 부분 응답 정책). 그 흡수가 실제로 완전한지는 목으로 대체되는 Facade
 * 테스트로는 확인되지 않으므로 여기서 실행 근거를 남긴다.
 */
class UserRestClientTest {

  private static final String BASE_URL = "http://localhost:8081";
  private static final String REQUEST_URL =
      BASE_URL + "/api/v1/internal/user/summaries?userIds=5,6";

  private MockRestServiceServer mockServer;
  private UserRestClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    mockServer = MockRestServiceServer.bindTo(builder).build();

    CustomSecurityProperties customSecurityProperties = new CustomSecurityProperties();
    customSecurityProperties.setInternalToken("test-token");

    client = new UserRestClient(builder.build(), customSecurityProperties);
  }

  private static String successBody() {
    return String.join(
        "\n",
        "{",
        "  \"is_success\": true,",
        "  \"code\": \"COMMON_200\",",
        "  \"message\": \"성공입니다.\",",
        "  \"result\": [",
        "    {\"user_id\": 5, \"name\": \"김소희\", \"email\": \"sohee@example.com\"},",
        "    {\"user_id\": 6, \"name\": \"이민주\", \"email\": \"minjoo@example.com\"}",
        "  ]",
        "}");
  }

  @Test
  @DisplayName("성공: 회원 요약을 맵으로 변환하고, 내부 API라 X-Internal-Token을 보낸다")
  void getUsers_returns_map_with_internal_token() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", "test-token"))
        .andRespond(withSuccess(successBody(), MediaType.APPLICATION_JSON));

    Map<Long, UserSummaryInfoResponse> users = client.getUsers(List.of(5L, 6L));

    assertThat(users).hasSize(2);
    assertThat(users.get(5L).name()).isEqualTo("김소희");
    assertThat(users.get(5L).email()).isEqualTo("sohee@example.com");
    assertThat(users.get(6L).name()).isEqualTo("이민주");
    mockServer.verify();
  }

  @Test
  @DisplayName("성공: 중복·null userId를 걸러 한 번만 요청한다")
  void getUsers_deduplicates_and_filters_null_ids() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andRespond(withSuccess(successBody(), MediaType.APPLICATION_JSON));

    client.getUsers(java.util.Arrays.asList(5L, 5L, null, 6L));

    mockServer.verify();
  }

  @Test
  @DisplayName("성공: 조회할 회원이 없으면 요청하지 않고 빈 맵을 반환한다")
  void getUsers_skips_request_when_no_ids() {
    Map<Long, UserSummaryInfoResponse> users = client.getUsers(List.of());

    assertThat(users).isEmpty();
    mockServer.verify(); // 기대한 요청이 없으므로 통과한다
  }

  @Test
  @DisplayName("실패 흡수: 5xx는 예외를 내지 않고 빈 맵으로 수렴한다")
  void getUsers_absorbs_server_error() {
    mockServer.expect(requestTo(REQUEST_URL)).andRespond(withServerError());

    assertThat(client.getUsers(List.of(5L, 6L))).isEmpty();
  }

  @Test
  @DisplayName("실패 흡수: 4xx(내부 토큰 불일치 등)도 빈 맵으로 수렴한다")
  void getUsers_absorbs_client_error() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{}"));

    assertThat(client.getUsers(List.of(5L, 6L))).isEmpty();
  }

  @Test
  @DisplayName("실패 흡수: 200이지만 result가 비어 있으면 빈 맵으로 수렴한다")
  void getUsers_absorbs_missing_result() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andRespond(
            withSuccess("{\"is_success\": true, \"result\": null}", MediaType.APPLICATION_JSON));

    assertThat(client.getUsers(List.of(5L, 6L))).isEmpty();
  }

  @Test
  @DisplayName("실패 흡수: result 배열에 null 원소나 user_id 없는 항목이 섞여도 나머지를 살린다")
  void getUsers_tolerates_broken_elements() {
    // RestClientException이 아닌 NPE로 새면 catch를 뚫고 500이 된다 — 목록 조회 전체가 죽는다.
    String body =
        String.join(
            "\n",
            "{",
            "  \"result\": [",
            "    null,",
            "    {\"name\": \"이름만\", \"email\": \"no-id@example.com\"},",
            "    {\"user_id\": 6, \"name\": \"이민주\", \"email\": \"minjoo@example.com\"}",
            "  ]",
            "}");
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

    Map<Long, UserSummaryInfoResponse> users = client.getUsers(List.of(5L, 6L));

    assertThat(users).hasSize(1);
    assertThat(users.get(6L).name()).isEqualTo("이민주");
  }

  @Test
  @DisplayName("성공: 이름·이메일이 null인 회원(소셜 가입)도 그대로 담는다")
  void getUsers_keeps_null_name_and_email() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andRespond(
            withSuccess(
                "{\"result\": [{\"user_id\": 5, \"name\": null, \"email\": null}]}",
                MediaType.APPLICATION_JSON));

    Map<Long, UserSummaryInfoResponse> users = client.getUsers(List.of(5L, 6L));

    assertThat(users).containsKey(5L);
    assertThat(users.get(5L).name()).isNull();
    assertThat(users.get(5L).email()).isNull();
  }
}
