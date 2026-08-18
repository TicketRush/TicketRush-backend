package com.ticketrush.global.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.util.StringUtils;

/**
 * 스케줄러 분산 락(ShedLock) 설정 (#439).
 *
 * <p>이 클래스에는 의도적으로 {@code @Configuration} 을 붙이지 않는다. common 은 전 서비스에 컴포넌트 스캔되므로({@link RedisConfig}
 * 참고) 붙이는 순간 ShedLock 을 쓰지 않는 payment/user/ticket/auth 까지 이 설정을 로드하게 된다. 락이 필요한 서비스가 각자
 * {@code @Import(ShedLockConfig.class)} 로 명시 활성화한다.
 *
 * <p>따라서 {@code @Import} 하지 않은 모듈에서는 {@code @SchedulerLock} 을 붙여도 아무 효과가 없다. 어드바이저 자체가 등록되지 않기
 * 때문이다.
 *
 * <p>락 키는 {@code job-lock:{applicationName}-{profile}:{lockName}} 형태로 만들어진다. 앞의 {@code job-lock} 과
 * 구분자는 RedisLockProvider 내부 구현이고, 가운데 조합만 이 클래스가 정한다. 이 조합 규칙을 바꾸면 무중단 배포 중 구/신 인스턴스가 서로 다른 키를 잡아
 * 같은 배치가 동시에 실행되므로 변경하지 않는다.
 *
 * <p>{@code @Configuration} 이 없으므로 이 클래스는 lite 모드로 등록된다. CGLIB 프록시가 없어 {@code @Bean} 메서드끼리 직접 호출하면
 * 싱글턴이 아니라 새 인스턴스가 만들어진다. 여기에 {@code @Bean} 을 더 추가하게 되면 {@link #lockProvider} 를 직접 부르지 말고 파라미터로
 * 주입받아야 한다. {@code LockProvider} 가 둘이 되면 락 자체가 갈린다.
 *
 * <p>{@code defaultLockAtMostFor} 는 현재 어디에도 적용되지 않는다. 사용처 아홉 곳이 모두 {@code lockAtMostFor} 를 직접 명시하기
 * 때문이다. 그렇다고 지울 수 있는 값도 아니다. {@code @EnableSchedulerLock} 의 이 속성에는 기본값이 없어 빼면 컴파일되지 않는다. 값을 바꾸면 나중에
 * {@code lockAtMostFor} 를 빠뜨린 스케줄러의 폴백만 달라지므로 그대로 둔다.
 */
@EnableSchedulerLock(defaultLockAtMostFor = "50s")
public class ShedLockConfig {

  private final String lockEnvironment;

  public ShedLockConfig(Environment env) {
    this.lockEnvironment = resolveLockEnvironment(env);
  }

  /**
   * 락 키의 네임스페이스 구간을 만든다.
   *
   * <p>{@code spring.application.name} 이 비어 있으면 기동을 실패시킨다. 중립적인 기본값을 두면 이름이 빠진 여러 서비스가 같은 네임스페이스를
   * 공유해 서로의 락을 가져가고, 증상이 "스케줄러가 가끔 안 돈다" 로만 나타나 원인을 찾기 어렵다. 값이 아예 없는 경우뿐 아니라 빈 문자열인 경우도 같은 결과를 낳으므로
   * 함께 막는다. 지금은 세 서비스 모두 yml 에 이름을 리터럴로 적어 두어 빈 값이 될 경로가 없지만, 환경변수로 옮겨지면 정의만 해 두고 값을 비우는 사고가 가능해진다.
   */
  static String resolveLockEnvironment(Environment env) {
    String applicationName = env.getProperty("spring.application.name");
    if (!StringUtils.hasText(applicationName)) {
      throw new IllegalStateException(
          "spring.application.name 이 비어 있어 락 네임스페이스를 만들 수 없다. "
              + "이름이 없는 서비스끼리 같은 네임스페이스를 공유하면 서로의 락을 가져간다.");
    }

    String[] activeProfiles = env.getActiveProfiles();
    String profile = activeProfiles.length > 0 ? activeProfiles[0] : "default";

    return applicationName + "-" + profile;
  }

  @Bean
  public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
    return new RedisLockProvider(connectionFactory, lockEnvironment);
  }
}
