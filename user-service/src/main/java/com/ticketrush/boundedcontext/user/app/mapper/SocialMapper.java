package com.ticketrush.boundedcontext.user.app.mapper;

import com.ticketrush.boundedcontext.user.app.dto.request.SocialLoginRequest;
import com.ticketrush.boundedcontext.user.domain.entity.SocialAccount;
import com.ticketrush.boundedcontext.user.domain.entity.User;
import com.ticketrush.boundedcontext.user.domain.types.SocialProvider;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SocialMapper {

  // SocialCreateRequest → User Entity 변환
  @Mapping(source = "name", target = "name")
  @Mapping(target = "userRole", constant = "MEMBER")
  User toUser(SocialLoginRequest request);

  // SocialCreateRequest → SocialAccount Entity 변환
  @Mapping(target = "user", ignore = true)
  @Mapping(source = "request.socialId", target = "providerUserId")
  @Mapping(source = "provider", target = "socialProvider")
  SocialAccount toSocialAccount(SocialLoginRequest request, SocialProvider provider);
}
