package com.ticketrush.boundedcontext.user.app.usecase;

import com.ticketrush.boundedcontext.user.app.dto.response.UserAuthInfoResponse;
import com.ticketrush.boundedcontext.user.domain.entity.User;
import com.ticketrush.boundedcontext.user.domain.entity.UserAccount;
import com.ticketrush.boundedcontext.user.out.repository.UserAccountRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetUserAuthInfoByUserIdUseCase {

  private final UserAccountRepository userAccountRepository;

  @Transactional(readOnly = true)
  public UserAuthInfoResponse execute(Long userId) {
    UserAccount userAccount =
        userAccountRepository
            .findByUserId(userId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.USER_NOT_FOUND));

    if (userAccount.getPassword() == null || userAccount.getPassword().isBlank()) {
      throw new BusinessException(ErrorStatus.USER_NOT_FOUND);
    }

    User user = userAccount.getUser();

    return new UserAuthInfoResponse(
        user.getId(), user.getEmail(), userAccount.getPassword(), user.getUserRole().name());
  }
}
