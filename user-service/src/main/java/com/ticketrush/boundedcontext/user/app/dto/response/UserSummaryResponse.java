package com.ticketrush.boundedcontext.user.app.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 내부 서비스가 회원을 표시용으로 보강할 때 쓰는 최소 정보 (#561).
 *
 * <p>{@link UserAuthInfoResponse}를 재사용하지 않는다 — 그쪽은 이름이 없고 {@code passwordHash}가 들어 있어, 표시 목적으로 넘기면
 * 자격증명이 불필요하게 서비스 경계를 넘는다.
 */
@Schema(description = "내부 통신용 회원 요약 응답 DTO")
public record UserSummaryResponse(
    @Schema(description = "회원 ID", example = "5") Long userId,
    @Schema(description = "회원 이름. 소셜 가입 경로에서는 null일 수 있다.", example = "김소희") String name,
    @Schema(description = "회원 이메일. 소셜 가입 경로에서는 null일 수 있다.", example = "user@example.com")
        String email) {}
