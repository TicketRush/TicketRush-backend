package com.ticketrush.boundedcontext.user.app.usecase;

import com.ticketrush.boundedcontext.user.app.dto.request.SignupRequest;
import com.ticketrush.boundedcontext.user.app.dto.response.SignupResponse;
import com.ticketrush.boundedcontext.user.domain.entity.User;
import com.ticketrush.boundedcontext.user.domain.entity.UserAccount;
import com.ticketrush.boundedcontext.user.domain.types.UserRole;
import com.ticketrush.boundedcontext.user.out.apiclient.AuthRestClient;
import com.ticketrush.boundedcontext.user.out.repository.UserAccountRepository;
import com.ticketrush.boundedcontext.user.out.repository.UserRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
  private final AuthRestClient authRestClient;

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

    validateName(request.name());
    log.info("[회원가입] 이름 검증 통과 email={}", request.email());

    validatePassword(request.password(), request.passwordConfirm());
    log.info("[회원가입] 비밀번호 검증 통과 email={}", request.email());

    String encodedPassword = passwordEncoder.encode(request.password());
    log.info("[회원가입] 비밀번호 암호화 완료 email={}", request.email());

    authRestClient.consumeSignupEmailVerification(request.email());
    log.info("[회원가입] 이메일 인증 완료 상태 소비 완료 email={}", request.email());

    User user =
        User.builder()
            .name(request.name())
            .email(request.email())
            .userRole(UserRole.MEMBER)
            .build();

    try {
      User savedUser = userRepository.saveAndFlush(user);
      log.info("[회원가입] User 저장 완료 userId={}, email={}", savedUser.getId(), savedUser.getEmail());

      UserAccount userAccount =
          UserAccount.builder().user(savedUser).password(encodedPassword).build();

      userAccountRepository.saveAndFlush(userAccount);
      log.info("[회원가입] UserAccount 저장 완료 userId={}", savedUser.getId());

      return new SignupResponse(savedUser.getId(), savedUser.getEmail(), savedUser.getName());

    } catch (DataIntegrityViolationException e) {
      log.warn("[회원가입] DB 제약 조건 위반 email={}", request.email(), e);
      throw new BusinessException(ErrorStatus.USER_EMAIL_ALREADY_EXISTS);
    }
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
