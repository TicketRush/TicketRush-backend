package com.ticketrush.boundedcontext.user.out;

import com.ticketrush.boundedcontext.user.domain.entity.UserAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

  @Query("select ua from UserAccount ua join fetch ua.user u where u.email = :email")
  Optional<UserAccount> findByEmail(@Param("email") String email);
}
