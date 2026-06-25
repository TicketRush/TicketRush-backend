package com.ticketrush.boundedcontext.ticket.domain.policy;

import java.time.LocalDateTime;

/** QR payload 생성 결과: 서명 토큰 문자열과 만료 시각. */
public record QrPayload(String payload, LocalDateTime expiresAt) {}
