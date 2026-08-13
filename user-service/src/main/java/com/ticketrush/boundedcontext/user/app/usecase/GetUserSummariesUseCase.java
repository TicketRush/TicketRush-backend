package com.ticketrush.boundedcontext.user.app.usecase;

import com.ticketrush.boundedcontext.user.app.dto.response.UserSummaryResponse;
import com.ticketrush.boundedcontext.user.out.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetUserSummariesUseCase {

  private final UserRepository userRepository;

  /**
   * 회원 ID 묶음으로 표시용 요약을 조회한다 (#561).
   *
   * <p><b>없는 ID는 예외 없이 결과에서 빠진다.</b> 호출 측(booking 관리자 목록)은 예매 행에 딸린 userId를 그대로 넘기는데, 회원이 탈퇴했거나 ID가
   * 어긋나도 목록 조회 전체가 실패해선 안 된다 — 그 행의 예매자 필드만 비면 된다.
   */
  @Transactional(readOnly = true)
  public List<UserSummaryResponse> execute(List<Long> userIds) {
    return userRepository.findAllById(userIds).stream()
        .map(user -> new UserSummaryResponse(user.getId(), user.getName(), user.getEmail()))
        .toList();
  }
}
