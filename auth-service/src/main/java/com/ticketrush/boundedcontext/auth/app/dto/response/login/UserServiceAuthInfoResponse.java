package com.ticketrush.boundedcontext.auth.app.dto.response.login;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UserServiceAuthInfoResponse(
    @JsonProperty("user_id") Long userId,
    String email,
    @JsonProperty("password_hash") String passwordHash,
    String role) {}
