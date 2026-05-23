package com.ticketrush.boundedcontext.user.app.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthApiResponse<T>(
    @JsonProperty("is_success") Boolean isSuccess,
    String code,
    String message,
    @JsonProperty("trace_id") String traceId,
    T result) {}
