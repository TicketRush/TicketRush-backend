package com.ticketrush.boundedcontext.user.app.dto.response;

import com.ticketrush.global.json.UtcLocalDateTimeSerializer;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

public record UserMeResponse(
    String name,
    String email,
    @JsonSerialize(using = UtcLocalDateTimeSerializer.class) LocalDateTime createdAt,
    String role) {}
