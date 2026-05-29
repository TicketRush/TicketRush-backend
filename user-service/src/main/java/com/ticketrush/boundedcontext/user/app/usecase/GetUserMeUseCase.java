package com.ticketrush.boundedcontext.user.app.usecase;

import com.ticketrush.boundedcontext.user.app.dto.response.UserMeResponse;
import com.ticketrush.boundedcontext.user.domain.entity.User;
import com.ticketrush.boundedcontext.user.out.repository.UserRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetUserMeUseCase {

  private final UserRepository userRepository;

  public UserMeResponse execute(Long userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.USER_NOT_FOUND));

    return new UserMeResponse(user.getName(), user.getEmail(), user.getCreatedAt());
  }
}
