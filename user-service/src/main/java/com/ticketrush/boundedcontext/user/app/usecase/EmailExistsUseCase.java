package com.ticketrush.boundedcontext.user.app.usecase;

import com.ticketrush.boundedcontext.user.app.dto.response.EmailExistsResponse;
import com.ticketrush.boundedcontext.user.out.UserRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class EmailExistsUseCase {

  private final UserRepository userRepository;

  public EmailExistsResponse execute(String email) {
    if (email == null || email.isBlank()) {
      throw new BusinessException(ErrorStatus.USER_EMAIL_REQUIRED);
    }

    boolean exists = userRepository.existsByEmail(email);
    return new EmailExistsResponse(exists);
  }
}
