package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.PerformanceServiceApplication;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(classes = PerformanceServiceApplication.class)
@EnableAutoConfiguration(
    exclude = {
      io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration.class,
      io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration.class
    })
class PerformanceServiceApplicationTests {

  @Autowired(required = false)
  private LockProvider lockProvider;

  @Test
  void contextLoads() {}

  /**
   * 락 설정은 common 에 있고 {@code SchedulingConfig} 의 {@code @Import} 로만 켜진다(#439). 그 한 줄이 사라지면 스케줄러는 계속
   * 돌지만 {@code @SchedulerLock} 이 무효가 되어 인스턴스마다 중복 실행되는데, 컴파일도 기동도 성공하므로 다른 어떤 테스트에도 걸리지 않는다.
   */
  @Test
  void activatesShedLockByImport() {
    assertThat(lockProvider).isNotNull();
  }
}
