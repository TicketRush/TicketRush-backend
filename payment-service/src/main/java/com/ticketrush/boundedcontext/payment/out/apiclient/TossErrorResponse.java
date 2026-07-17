package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Toss Payments 에러 응답 body 매핑.
 *
 * <p>응답 포맷: {@code { "code": "...", "message": "..." }}
 *
 * <p>참고: https://docs.tosspayments.com/reference/error-codes
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossErrorResponse(String code, String message) {}
