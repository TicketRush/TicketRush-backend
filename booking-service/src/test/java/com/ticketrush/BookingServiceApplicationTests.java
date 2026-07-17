package com.ticketrush;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(classes = BookingServiceApplicationTests.class)
class BookingServiceApplicationTests {

  @Test
  void contextLoads() {}
}
