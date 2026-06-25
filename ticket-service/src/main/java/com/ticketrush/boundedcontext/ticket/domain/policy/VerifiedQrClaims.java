package com.ticketrush.boundedcontext.ticket.domain.policy;

/**
 * QR JWT 검증 결과. 서명·만료 검증을 통과한 토큰에서 추출한 입장권 식별자만 담는다. 예매 확정/취소·사용 여부 등 가변 상태는 신뢰 소스인 DB에서 다시 읽으므로
 * 여기에 포함하지 않는다(생성 시점 스냅샷인 JWT의 {@code st} 클레임은 입장 판정에 쓰지 않는다).
 */
public record VerifiedQrClaims(Long ticketId) {}
