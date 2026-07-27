package com.ticketrush;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(classes = TicketServiceApplication.class)
class TicketServiceApplicationTests {

  @Test
  void contextLoads() {}
}
