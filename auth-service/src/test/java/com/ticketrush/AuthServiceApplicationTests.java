package com.ticketrush;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(classes = AuthServiceApplicationTests.class)
class AuthServiceApplicationTests {

  @Test
  void contextLoads() {}
}
