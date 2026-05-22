package com.ticketrush.boundedcontext.user.app.mapper;

import com.ticketrush.boundedcontext.user.app.dto.request.SignupRequest;
import com.ticketrush.boundedcontext.user.domain.entity.User;
import com.ticketrush.boundedcontext.user.domain.entity.UserAccount;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SignupMapper {

  @Mapping(source = "name", target = "name")
  @Mapping(source = "email", target = "email")
  @Mapping(target = "userRole", constant = "MEMBER")
  User toUser(SignupRequest request);

  @Mapping(target = "user", source = "user")
  @Mapping(target = "password", source = "encodedPassword")
  UserAccount toUserAccount(User user, String encodedPassword);
}
