package com.ticketrush.boundedcontext.user.app.dto.response;

public record UserAuthInfoResponse(Long userId, String email, String password, String role) {}
