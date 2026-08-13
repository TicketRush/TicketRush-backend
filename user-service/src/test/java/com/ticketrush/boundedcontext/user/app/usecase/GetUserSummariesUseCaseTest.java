package com.ticketrush.boundedcontext.user.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.user.app.dto.response.UserSummaryResponse;
import com.ticketrush.boundedcontext.user.domain.entity.User;
import com.ticketrush.boundedcontext.user.domain.types.UserRole;
import com.ticketrush.boundedcontext.user.out.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GetUserSummariesUseCaseTest {

  @InjectMocks private GetUserSummariesUseCase getUserSummariesUseCase;

  @Mock private UserRepository userRepository;

  private static User user(Long id, String name, String email) {
    User user = User.builder().name(name).email(email).userRole(UserRole.MEMBER).build();
    ReflectionTestUtils.setField(user, "id", id);
    return user;
  }

  @Test
  @DisplayName("성공: userId 묶음으로 이름·이메일을 조회한다")
  void execute_returns_summaries() {
    // given
    given(userRepository.findAllById(List.of(1L, 2L)))
        .willReturn(
            List.of(user(1L, "김소희", "sohee@example.com"), user(2L, "이민주", "minjoo@example.com")));

    // when
    List<UserSummaryResponse> responses = getUserSummariesUseCase.execute(List.of(1L, 2L));

    // then
    assertThat(responses)
        .containsExactly(
            new UserSummaryResponse(1L, "김소희", "sohee@example.com"),
            new UserSummaryResponse(2L, "이민주", "minjoo@example.com"));
  }

  @Test
  @DisplayName("성공: 존재하지 않는 userId는 예외 없이 결과에서 빠진다")
  void execute_skips_unknown_user_ids() {
    // given: 2번 회원은 탈퇴했거나 없다
    given(userRepository.findAllById(List.of(1L, 2L)))
        .willReturn(List.of(user(1L, "김소희", "sohee@example.com")));

    // when
    List<UserSummaryResponse> responses = getUserSummariesUseCase.execute(List.of(1L, 2L));

    // then: 한 건 때문에 조회 전체가 실패해선 안 된다
    assertThat(responses).extracting(UserSummaryResponse::userId).containsExactly(1L);
  }

  @Test
  @DisplayName("성공: 이름·이메일이 비어 있는 회원(소셜 가입)도 null로 그대로 내려간다")
  void execute_allows_null_name_and_email() {
    // given
    given(userRepository.findAllById(List.of(1L))).willReturn(List.of(user(1L, null, null)));

    // when
    List<UserSummaryResponse> responses = getUserSummariesUseCase.execute(List.of(1L));

    // then
    assertThat(responses).containsExactly(new UserSummaryResponse(1L, null, null));
  }
}
