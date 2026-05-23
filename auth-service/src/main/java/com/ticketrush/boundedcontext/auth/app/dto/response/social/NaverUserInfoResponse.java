package com.ticketrush.boundedcontext.auth.app.dto.response.social;

public record NaverUserInfoResponse(String resultcode, String message, Response response) {

  public record Response(String id, String name, String email) {}
}
