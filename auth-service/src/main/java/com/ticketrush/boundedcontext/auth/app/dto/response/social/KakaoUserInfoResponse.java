package com.ticketrush.boundedcontext.auth.app.dto.response.social;

/*
 auth-service가 access token으로 카카오 사용자 정보 요청을 할 때, 카카오의 사용자 정보 응답 반환 DTO
  카카오 사용자 정보 조회 API 응답 매핑
  카카오 JSON을 그대로 매핑해옴
*/
public record KakaoUserInfoResponse(
    Long id, // 카카오 고유 식별자 ID
    Properties properties) {
  public record Properties(String nickname) {}
}
