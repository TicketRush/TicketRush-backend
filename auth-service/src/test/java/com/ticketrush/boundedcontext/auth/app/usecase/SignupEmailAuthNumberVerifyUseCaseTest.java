package com.ticketrush.boundedcontext.auth.app.usecase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.auth.app.dto.request.SignupEmailAuthNumberVerifyRequest;
import com.ticketrush.boundedcontext.auth.out.repository.RedisRepository;
import com.ticketrush.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SignupEmailAuthNumberVerifyUseCaseTest {

  @Mock private RedisRepository redisRepository;

  @InjectMocks private SignupEmailAuthNumberVerifyUseCase signupEmailAuthNumberVerifyUseCase;

  @Test
  @DisplayName("회원가입 이메일 인증번호 검증에 성공한다")
  void execute_success() {
    // given
    String email = "test@test.com";
    String authNumber = "123456";

    SignupEmailAuthNumberVerifyRequest request =
        new SignupEmailAuthNumberVerifyRequest(email, authNumber);

    given(redisRepository.getSignupEmailAuthNumber(email)).willReturn(authNumber);
    given(redisRepository.getSignupEmailAuthVerifyAttemptCount(email)).willReturn(0);

    // when & then
    assertThatCode(() -> signupEmailAuthNumberVerifyUseCase.execute(request))
        .doesNotThrowAnyException();

    verify(redisRepository).saveSignupEmailAuthVerified(email);
    verify(redisRepository).deleteSignupEmailAuthNumber(email);
    verify(redisRepository).deleteSignupEmailAuthVerifyAttempt(email);
  }

  @Test
  @DisplayName("Redis에 저장된 인증번호가 없으면 예외가 발생한다")
  void execute_authNumberNotFound() {
    // given
    String email = "test@test.com";
    String inputAuthNumber = "123456";

    SignupEmailAuthNumberVerifyRequest request =
        new SignupEmailAuthNumberVerifyRequest(email, inputAuthNumber);

    given(redisRepository.getSignupEmailAuthNumber(email)).willReturn(null);

    // when & then
    assertThatThrownBy(() -> signupEmailAuthNumberVerifyUseCase.execute(request))
        .isInstanceOf(BusinessException.class);

    verify(redisRepository, never()).saveSignupEmailAuthVerified(email);
    verify(redisRepository, never()).deleteSignupEmailAuthNumber(email);
    verify(redisRepository, never()).deleteSignupEmailAuthVerifyAttempt(email);
  }

  @Test
  @DisplayName("인증번호가 일치하지 않으면 실패 횟수를 증가시키고 예외가 발생한다")
  void execute_authNumberNotMatch() {
    // given
    String email = "test@test.com";
    String savedAuthNumber = "123456";
    String inputAuthNumber = "654321";

    SignupEmailAuthNumberVerifyRequest request =
        new SignupEmailAuthNumberVerifyRequest(email, inputAuthNumber);

    given(redisRepository.getSignupEmailAuthNumber(email)).willReturn(savedAuthNumber);
    given(redisRepository.getSignupEmailAuthVerifyAttemptCount(email)).willReturn(0);
    given(redisRepository.increaseSignupEmailAuthVerifyAttempt(email)).willReturn(1L);

    // when & then
    assertThatThrownBy(() -> signupEmailAuthNumberVerifyUseCase.execute(request))
        .isInstanceOf(BusinessException.class);

    verify(redisRepository).increaseSignupEmailAuthVerifyAttempt(email);
    verify(redisRepository, never()).saveSignupEmailAuthVerified(email);
    verify(redisRepository, never()).deleteSignupEmailAuthNumber(email);
    verify(redisRepository, never()).deleteSignupEmailAuthVerifyAttempt(email);
  }

  @Test
  @DisplayName("인증번호 불일치 횟수가 최대 횟수에 도달하면 인증번호와 실패 횟수 키를 삭제하고 예외가 발생한다")
  void execute_authNumberNotMatch_attemptExceeded() {
    // given
    String email = "test@test.com";
    String savedAuthNumber = "123456";
    String inputAuthNumber = "654321";

    SignupEmailAuthNumberVerifyRequest request =
        new SignupEmailAuthNumberVerifyRequest(email, inputAuthNumber);

    given(redisRepository.getSignupEmailAuthNumber(email)).willReturn(savedAuthNumber);
    given(redisRepository.getSignupEmailAuthVerifyAttemptCount(email)).willReturn(4);
    given(redisRepository.increaseSignupEmailAuthVerifyAttempt(email)).willReturn(5L);

    // when & then
    assertThatThrownBy(() -> signupEmailAuthNumberVerifyUseCase.execute(request))
        .isInstanceOf(BusinessException.class);

    verify(redisRepository).increaseSignupEmailAuthVerifyAttempt(email);
    verify(redisRepository).deleteSignupEmailAuthNumber(email);
    verify(redisRepository).deleteSignupEmailAuthVerifyAttempt(email);
    verify(redisRepository, never()).saveSignupEmailAuthVerified(email);
  }

  @Test
  @DisplayName("인증번호 검증 실패 횟수가 이미 최대 횟수 이상이면 인증번호와 실패 횟수 키를 삭제하고 예외가 발생한다")
  void execute_alreadyAttemptExceeded() {
    // given
    String email = "test@test.com";
    String savedAuthNumber = "123456";
    String inputAuthNumber = "123456";

    SignupEmailAuthNumberVerifyRequest request =
        new SignupEmailAuthNumberVerifyRequest(email, inputAuthNumber);

    given(redisRepository.getSignupEmailAuthNumber(email)).willReturn(savedAuthNumber);
    given(redisRepository.getSignupEmailAuthVerifyAttemptCount(email)).willReturn(5);

    // when & then
    assertThatThrownBy(() -> signupEmailAuthNumberVerifyUseCase.execute(request))
        .isInstanceOf(BusinessException.class);

    verify(redisRepository).deleteSignupEmailAuthNumber(email);
    verify(redisRepository).deleteSignupEmailAuthVerifyAttempt(email);
    verify(redisRepository, never()).increaseSignupEmailAuthVerifyAttempt(email);
    verify(redisRepository, never()).saveSignupEmailAuthVerified(email);
  }
}
