package com.ticketrush.boundedcontext.user.app.usecase;

import com.ticketrush.boundedcontext.user.app.dto.request.SignupRequest;
import com.ticketrush.boundedcontext.user.app.dto.response.SignupResponse;
import com.ticketrush.boundedcontext.user.domain.entity.User;
import com.ticketrush.boundedcontext.user.domain.entity.UserAccount;
import com.ticketrush.boundedcontext.user.domain.types.UserRole;
import com.ticketrush.boundedcontext.user.out.EmailVerificationClient;
import com.ticketrush.boundedcontext.user.out.UserAccountRepository;
import com.ticketrush.boundedcontext.user.out.UserRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SignupUseCase {

  private final UserRepository userRepository;
  private final UserAccountRepository userAccountRepository;
  private final PasswordEncoder passwordEncoder;
  private final EmailVerificationClient emailVerificationClient;

  private static final Pattern PASSWORD_PATTERN =
      Pattern.compile(
          "^(?=.*[a-z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]).{12,}$");

  public SignupResponse execute(SignupRequest request) {

    log.info("[회원가입] 요청 시작 email={}", request.email());

    validateEmail(request.email());

    log.info("[회원가입] 이메일 필수 검증 통과 email={}", request.email());

    boolean existsEmail = userRepository.existsByEmail(request.email());
    log.info("[회원가입] 이메일 중복 확인 결과 email={}, exists={}", request.email(), existsEmail);

    if (existsEmail) {
      throw new BusinessException(ErrorStatus.USER_EMAIL_ALREADY_EXISTS);
    }

    boolean verified = emailVerificationClient.isVerified(request.email());
    log.info("[회원가입] 이메일 인증 완료 여부 확인 결과 email={}, verified={}", request.email(), verified);

    if (!verified) {
      throw new BusinessException(ErrorStatus.EMAIL_VERIFICATION_REQUIRED);
    }

    validateName(request.name());
    log.info("[회원가입] 이름 검증 통과 email={}", request.email());

    validatePassword(request.password(), request.passwordConfirm());
    log.info("[회원가입] 비밀번호 검증 통과 email={}", request.email());

    User user =
        User.builder()
            .name(request.name())
            .email(request.email())
            .userRole(UserRole.MEMBER)
            .build();

    User savedUser = userRepository.save(user);
    log.info("[회원가입] User 저장 완료 userId={}, email={}", savedUser.getId(), savedUser.getEmail());

    String encodedPassword = passwordEncoder.encode(request.password());

    UserAccount userAccount =
        UserAccount.builder().user(savedUser).password(encodedPassword).build();

    userAccountRepository.save(userAccount);
    log.info("[회원가입] UserAccount 저장 완료 userId={}", savedUser.getId());

    return new SignupResponse(savedUser.getId(), savedUser.getEmail(), savedUser.getName());
  }

  private void validateEmail(String email) {
    if (email == null || email.isBlank()) {
      throw new BusinessException(ErrorStatus.USER_EMAIL_REQUIRED);
    }
  }

  private void validateName(String name) {
    if (name == null || name.isBlank()) {
      throw new BusinessException(ErrorStatus.USER_NAME_REQUIRED);
    }
  }

  private void validatePassword(String password, String passwordConfirm) {

    if (password == null
        || password.isBlank()
        || passwordConfirm == null
        || passwordConfirm.isBlank()) {
      throw new BusinessException(ErrorStatus.USER_PASSWORD_REQUIRED);
    }

    if (!password.equals(passwordConfirm)) {
      throw new BusinessException(ErrorStatus.USER_PASSWORD_NOT_MATCH);
    }

    if (!PASSWORD_PATTERN.matcher(password).matches()) {
      throw new BusinessException(ErrorStatus.USER_PASSWORD_INVALID);
    }
  }
}
