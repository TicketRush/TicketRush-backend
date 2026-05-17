package com.ticketrush.boundedcontext.user.out;

import com.ticketrush.boundedcontext.user.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

  // DB에 저장된 이메일을 기준으로 중복 검사
  boolean existsByEmail(String email);
}
