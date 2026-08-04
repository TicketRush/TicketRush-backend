package com.ticketrush.boundedcontext.booking.out.apiclient;

import com.ticketrush.boundedcontext.booking.out.apiclient.dto.UserSummariesApiResponse;
import com.ticketrush.boundedcontext.booking.out.apiclient.dto.UserSummaryInfoResponse;
import com.ticketrush.global.config.CustomSecurityProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 관리자 예매 목록 응답 보강용 회원 조회 클라이언트 (#561). user-service의 <b>내부</b> API를 호출하므로 내부 토큰을 싣는다.
 *
 * <p><b>실패해도 예외를 밖으로 내지 않는다(#560 부분 응답 정책).</b> 여기서 던지면 user-service 장애가 관리자 예매 목록 전체를 죽인다. booking
 * 코어 필드는 이미 DB에서 확정됐으므로, 예매자 필드만 비운 채 응답하고 프론트가 {@code userId}로 재조회하게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserRestClient {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  private final RestClient userServiceRestClient;
  private final CustomSecurityProperties customSecurityProperties;

  /** 벌크 조회. 통신 실패·본문 결손은 모두 빈 맵으로 수렴하며, 조회되지 않은 회원은 맵에서 빠진다. */
  public Map<Long, UserSummaryInfoResponse> getUsers(List<Long> userIds) {
    // 중복 제거를 호출 측에 맡기지 않는다 — 중복이 오면 user-service 요청만 길어지고, 응답 매핑도 같은 키를
    // 두 번 만난다. 계약을 이 안에서 닫아 둔다.
    List<Long> distinctUserIds = userIds.stream().filter(Objects::nonNull).distinct().toList();
    if (distinctUserIds.isEmpty()) {
      return Map.of();
    }

    try {
      UserSummariesApiResponse response =
          userServiceRestClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/api/v1/internal/user/summaries")
                          .queryParam(
                              "userIds",
                              distinctUserIds.stream()
                                  .map(String::valueOf)
                                  .collect(Collectors.joining(",")))
                          .build())
              .header(INTERNAL_TOKEN_HEADER, customSecurityProperties.getInternalToken())
              .retrieve()
              .body(UserSummariesApiResponse.class);

      if (response == null || response.result() == null) {
        log.warn("[UserRestClient] 회원 조회 200 응답이나 result가 비어 있음 userIds={}", distinctUserIds);
        return Map.of();
      }

      // 계약을 이 안에서 닫는다. toMap은 키 중복이면 IllegalStateException, 키가 null이면 NPE를 던지는데
      // 둘 다 RestClientException이 아니라 아래 catch를 뚫고 500이 된다(#560 리뷰가 좌석 쪽에서 잡은 구멍).
      // 원소 자체의 null도 같은 이유로 건너뛴다 — 배열에 null이 섞여 오면 user.userId()에서 NPE가 난다.
      Map<Long, UserSummaryInfoResponse> users = new HashMap<>();
      for (UserSummaryInfoResponse user : response.result()) {
        if (user != null && user.userId() != null) {
          users.put(user.userId(), user);
        }
      }
      return users;
    } catch (RestClientException e) {
      log.warn(
          "[UserRestClient] 회원 조회 실패, 예매자 이름·이메일은 null로 응답합니다. userIds={}", distinctUserIds, e);
      return Map.of();
    }
  }
}
