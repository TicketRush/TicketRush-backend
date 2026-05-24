package com.ticketrush.boundedcontext.user.app.dto.response;

public record UserAuthInfoResponse(Long userId, String email, String passwordHash, String role) {}
