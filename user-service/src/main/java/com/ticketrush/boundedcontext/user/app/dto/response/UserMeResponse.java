package com.ticketrush.boundedcontext.user.app.dto.response;

import java.time.LocalDateTime;

public record UserMeResponse(String name, String email, LocalDateTime createdAt) {}
