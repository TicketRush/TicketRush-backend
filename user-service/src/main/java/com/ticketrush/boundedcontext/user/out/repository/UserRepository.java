package com.ticketrush.boundedcontext.user.out.repository;

import com.ticketrush.boundedcontext.user.domain.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

  // DB에 저장된 이메일을 기준으로 중복 검사
  boolean existsByEmail(String email);

  // 로그인 시 이메일로 회원 조회
  Optional<User> findByEmail(String email);
}
