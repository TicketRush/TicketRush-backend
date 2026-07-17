package com.ticketrush.boundedcontext.user.app.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SignupRequest(
    String name,
    String email,
    String password,
    @JsonProperty("password_confirm") String passwordConfirm) {}
