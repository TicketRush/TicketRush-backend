package com.ticketrush.boundedcontext.auth.app.usecase;

import com.ticketrush.boundedcontext.auth.out.repository.RedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class SocialLogoutUseCase {

  private final RedisRepository redisRepository;

  public void execute(Long userId) {

    // Refresh Token 삭제
    redisRepository.deleteRefreshToken(userId);
  }
}
