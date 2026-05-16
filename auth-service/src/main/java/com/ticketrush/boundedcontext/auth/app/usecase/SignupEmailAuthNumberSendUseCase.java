package com.ticketrush.boundedcontext.auth.app.usecase;

import com.ticketrush.boundedcontext.auth.app.dto.request.SignupEmailAuthNumberSendRequest;
import com.ticketrush.boundedcontext.auth.app.dto.response.SignupEmailAuthNumberSendResponse;
import com.ticketrush.boundedcontext.auth.out.apiclient.EmailSender;
import com.ticketrush.boundedcontext.auth.out.apiclient.UserServiceClient;
import com.ticketrush.boundedcontext.auth.out.repository.RedisRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.security.SecureRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SignupEmailAuthNumberSendUseCase {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final UserServiceClient userServiceClient;
  private final RedisRepository redisRepository;
  private final EmailSender emailSender;

  public SignupEmailAuthNumberSendResponse execute(SignupEmailAuthNumberSendRequest request) {
    String email = request.email();

    boolean existsEmail = userServiceClient.existsByEmail(email);

    if (existsEmail) {
      throw new BusinessException(ErrorStatus.AUTH_EMAIL_ALREADY_EXISTS);
    }

    boolean cooldownSaved = redisRepository.saveSignupEmailAuthCooldownIfAbsent(email);

    if (!cooldownSaved) {
      throw new BusinessException(ErrorStatus.AUTH_EMAIL_AUTH_NUMBER_SEND_TOO_FREQUENT);
    }

    String authNumber = createAuthNumber();

    redisRepository.saveSignupEmailAuthNumber(email, authNumber);

    try {
      emailSender.send(email, "[회원가입] 이메일 인증 번호", "인증 번호는 " + authNumber + " 입니다.");
    } catch (Exception e) {
      redisRepository.deleteSignupEmailAuthNumber(email);
      redisRepository.deleteSignupEmailAuthCooldown(email);

      log.error("회원가입 이메일 인증 번호 발송 실패 email={}", email, e);

      log.error("회원가입 이메일 인증 번호 발송 실패 email={}", email, e);

      throw new BusinessException(ErrorStatus.AUTH_EMAIL_SEND_FAILED);
    }

    log.info("회원가입 이메일 인증 번호 발송 완료 email={}", email);

    return new SignupEmailAuthNumberSendResponse("이메일 인증 번호가 발송되었습니다.");
  }

  private String createAuthNumber() {
    int number = SECURE_RANDOM.nextInt(900000) + 100000;
    return String.valueOf(number);
  }
}