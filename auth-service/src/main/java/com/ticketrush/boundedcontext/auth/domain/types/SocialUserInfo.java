package com.ticketrush.boundedcontext.auth.domain.types;

// 카카오,네이버,구글에서 받아온 "원본 유저 정보"
public record SocialUserInfo(
    String socialId, SocialProvider socialProvider, String name, String email) {}
