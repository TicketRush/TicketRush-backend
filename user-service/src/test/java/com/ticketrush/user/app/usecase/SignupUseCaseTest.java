package com.ticketrush.user.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.user.app.dto.request.SignupRequest;
import com.ticketrush.boundedcontext.user.app.dto.response.SignupResponse;
import com.ticketrush.boundedcontext.user.app.usecase.SignupUseCase;
import com.ticketrush.boundedcontext.user.domain.entity.User;
import com.ticketrush.boundedcontext.user.domain.entity.UserAccount;
import com.ticketrush.boundedcontext.user.out.EmailVerificationClient;
import com.ticketrush.boundedcontext.user.out.UserAccountRepository;
import com.ticketrush.boundedcontext.user.out.UserRepository;
import com.ticketrush.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SignupUseCaseTest {

  @InjectMocks private SignupUseCase signupUseCase;

  @Mock private UserRepository userRepository;

  @Mock private UserAccountRepository userAccountRepository;

  @Mock private PasswordEncoder passwordEncoder;

  @Mock private EmailVerificationClient emailVerificationClient;

  @Test
  @DisplayName("인증 완료된 이메일과 유효한 비밀번호로 회원가입에 성공한다")
  void signupSuccess() {
    // given
    SignupRequest request =
        new SignupRequest("김혜림", "test@example.com", "asdfgh123456!", "asdfgh123456!");

    given(userRepository.existsByEmail(request.email())).willReturn(false);
    given(emailVerificationClient.isVerified(request.email())).willReturn(true);
    given(passwordEncoder.encode(request.password())).willReturn("encoded-password");

    given(userRepository.save(any(User.class)))
        .willAnswer(
            invocation -> {
              User savedUser = invocation.getArgument(0);
              ReflectionTestUtils.setField(savedUser, "id", 1L);
              return savedUser;
            });

    // when
    SignupResponse response = signupUseCase.execute(request);

    // then
    assertThat(response.userId()).isEqualTo(1L);
    assertThat(response.email()).isEqualTo("test@example.com");
    assertThat(response.name()).isEqualTo("김혜림");

    ArgumentCaptor<UserAccount> userAccountCaptor = ArgumentCaptor.forClass(UserAccount.class);

    verify(userAccountRepository).save(userAccountCaptor.capture());

    UserAccount savedUserAccount = userAccountCaptor.getValue();

    assertThat(savedUserAccount.getUser().getEmail()).isEqualTo("test@example.com");
    assertThat(savedUserAccount.getPassword()).isEqualTo("encoded-password");
  }

  @Test
  @DisplayName("이미 가입된 이메일이면 회원가입에 실패한다")
  void signupFailWhenEmailAlreadyExists() {
    // given
    SignupRequest request =
        new SignupRequest("김혜림", "duplicate@example.com", "asdfgh123456!", "asdfgh123456!");

    given(userRepository.existsByEmail(request.email())).willReturn(true);

    // when & then
    assertThatThrownBy(() -> signupUseCase.execute(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("이미 가입된 이메일입니다.");

    verify(emailVerificationClient, never()).isVerified(any());
    verify(userRepository, never()).save(any(User.class));
    verify(userAccountRepository, never()).save(any(UserAccount.class));
  }

  @Test
  @DisplayName("이메일 인증이 완료되지 않았으면 회원가입에 실패한다")
  void signupFailWhenEmailNotVerified() {
    // given
    SignupRequest request =
        new SignupRequest("김혜림", "not-verified@example.com", "asdfgh123456!", "asdfgh123456!");

    given(userRepository.existsByEmail(request.email())).willReturn(false);
    given(emailVerificationClient.isVerified(request.email())).willReturn(false);

    // when & then
    assertThatThrownBy(() -> signupUseCase.execute(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("이메일 인증번호 일치 확인 인증이 필요합니다.");

    verify(userRepository, never()).save(any(User.class));
    verify(userAccountRepository, never()).save(any(UserAccount.class));
  }

  @Test
  @DisplayName("비밀번호와 비밀번호 확인이 일치하지 않으면 회원가입에 실패한다")
  void signupFailWhenPasswordNotMatch() {
    // given
    SignupRequest request =
        new SignupRequest("김혜림", "test@example.com", "asdfgh123456!", "asdfgh123456?");

    given(userRepository.existsByEmail(request.email())).willReturn(false);
    given(emailVerificationClient.isVerified(request.email())).willReturn(true);

    // when & then
    assertThatThrownBy(() -> signupUseCase.execute(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("비밀번호와 비밀번호 확인이 일치하지 않습니다.");

    verify(userRepository, never()).save(any(User.class));
    verify(userAccountRepository, never()).save(any(UserAccount.class));
  }

  @Test
  @DisplayName("비밀번호 형식이 조건을 만족하지 않으면 회원가입에 실패한다")
  void signupFailWhenPasswordInvalid() {
    // given
    SignupRequest request =
        new SignupRequest("김혜림", "test@example.com", "asdfgh123456", "asdfgh123456");

    given(userRepository.existsByEmail(request.email())).willReturn(false);
    given(emailVerificationClient.isVerified(request.email())).willReturn(true);

    // when & then
    assertThatThrownBy(() -> signupUseCase.execute(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("비밀번호는 소문자, 숫자, 특수문자를 포함하여 12자 이상이어야 합니다.");

    verify(userRepository, never()).save(any(User.class));
    verify(userAccountRepository, never()).save(any(UserAccount.class));
  }

  @Test
  @DisplayName("비밀번호가 비어 있으면 회원가입에 실패한다")
  void signupFailWhenPasswordBlank() {
    // given
    SignupRequest request = new SignupRequest("김혜림", "test@example.com", "", "");

    given(userRepository.existsByEmail(request.email())).willReturn(false);
    given(emailVerificationClient.isVerified(request.email())).willReturn(true);

    // when & then
    assertThatThrownBy(() -> signupUseCase.execute(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("비밀번호를 입력해주세요.");

    verify(userRepository, never()).save(any(User.class));
    verify(userAccountRepository, never()).save(any(UserAccount.class));
  }
}
