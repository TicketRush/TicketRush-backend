package com.ticketrush.boundedcontext.auth.app.dto.response.signup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserServiceApiResponse<T>(
    @JsonProperty("is_success") Boolean isSuccess,
    String code,
    String message,
    @JsonProperty("trace_id") String traceId,
    T result) {}
