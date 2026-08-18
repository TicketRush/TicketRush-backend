package com.ticketrush.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.ExtendedLockConfigurationExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.stereotype.Component;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ShedLockConfig} 는 {@code @Configuration} 없이 {@code @Import} 로만 활성화되는 설정이라, 그 활성화 방식 자체가 이
 * 클래스가 지키는 계약이다.
 *
 * <p>락 키의 네임스페이스 구간({@code {applicationName}-{profile}})도 여기서 고정한다. 이 문자열이 바뀌면 무중단 배포 중 구/신 인스턴스가
 * 서로 다른 락을 잡아 같은 배치가 동시에 실행되는데, 그 사고는 배포 중에만 드러나 테스트 밖에서는 잡을 방법이 없다.
 */
class ShedLockConfigTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
          .withPropertyValues("spring.application.name=test-service");

  @Test
  @DisplayName("성공: @Import 한 모듈에서는 LockProvider 가 생성된다")
  void provides_lock_provider_when_imported() {
    contextRunner
        .withUserConfiguration(ImportingConfig.class)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(LockProvider.class)
                    .getBean(LockProvider.class)
                    .isInstanceOf(RedisLockProvider.class));
  }

  @Test
  @DisplayName("성공: @Import 하면 @SchedulerLock 을 처리할 어드바이저까지 함께 등록된다")
  void registers_lock_advisor_when_imported() {
    contextRunner
        .withUserConfiguration(ImportingConfig.class)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(ExtendedLockConfigurationExtractor.class));
  }

  @Test
  @DisplayName("성공: @Import 하지 않은 모듈에는 LockProvider 가 없다")
  void does_not_provide_lock_provider_without_import() {
    contextRunner.run(
        context -> assertThat(context).hasNotFailed().doesNotHaveBean(LockProvider.class));
  }

  @Test
  @DisplayName("실패: spring.application.name 이 없으면 기동에 실패한다")
  void fails_fast_when_application_name_is_missing() {
    new ApplicationContextRunner()
        .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
        .withUserConfiguration(ImportingConfig.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("spring.application.name"));
  }

  @Test
  @DisplayName("성공: 락 네임스페이스는 애플리케이션 이름과 첫 활성 프로파일을 잇는다")
  void joins_application_name_and_active_profile() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("spring.application.name", "seat-service");
    env.setActiveProfiles("local");

    assertThat(ShedLockConfig.resolveLockEnvironment(env)).isEqualTo("seat-service-local");
  }

  @Test
  @DisplayName("성공: 활성 프로파일이 없으면 default 로 대체한다")
  void falls_back_to_default_profile() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("spring.application.name", "seat-service");

    assertThat(ShedLockConfig.resolveLockEnvironment(env)).isEqualTo("seat-service-default");
  }

  @Test
  @DisplayName("성공: 활성 프로파일이 여럿이면 첫 번째만 쓴다")
  void uses_only_first_active_profile() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("spring.application.name", "seat-service");
    env.setActiveProfiles("prod", "monitoring");

    assertThat(ShedLockConfig.resolveLockEnvironment(env)).isEqualTo("seat-service-prod");
  }

  @Test
  @DisplayName("실패: 애플리케이션 이름이 없으면 중립값으로 넘어가지 않고 예외를 던진다")
  void rejects_missing_application_name() {
    assertThatThrownBy(() -> ShedLockConfig.resolveLockEnvironment(new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.application.name");
  }

  @Test
  @DisplayName("실패: 애플리케이션 이름이 빈 문자열이어도 예외를 던진다")
  void rejects_blank_application_name() {
    // 값 부재는 getRequiredProperty 만으로도 막혔다. 이 가드가 실제로 늘린 방어는 빈 문자열 쪽이다.
    MockEnvironment env = new MockEnvironment();
    env.setProperty("spring.application.name", "   ");

    assertThatThrownBy(() -> ShedLockConfig.resolveLockEnvironment(env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.application.name");
  }

  @Test
  @DisplayName("성공: 조합한 네임스페이스가 실제 Redis 락 키에 그대로 실린다")
  void builds_redis_key_from_resolved_environment() {
    contextRunner
        .withUserConfiguration(ImportingConfig.class)
        .run(
            context -> {
              LockProvider provider = context.getBean(LockProvider.class);

              // buildKey 는 package-private 이라 리플렉션으로 부른다. 이 단언이 없으면 provider 를
              // 1-arg 생성자로 바꿔도(= environment 가 라이브러리 기본값으로 대체돼도) 나머지 테스트가
              // 모두 통과하며, 무중단 배포 중 구/신 인스턴스가 서로 다른 락을 잡게 된다.
              String key =
                  ReflectionTestUtils.invokeMethod(provider, "buildKey", "outboxRelay-seat");

              assertThat(key).isEqualTo("job-lock:test-service-default:outboxRelay-seat");
            });
  }

  @Test
  @DisplayName("성공: 컴포넌트 스캔에 잡히는 어노테이션이 없다")
  void is_not_discoverable_by_component_scan() {
    // common 은 전 서비스에 스캔되므로 @Configuration(=메타 @Component)이 붙는 순간 ShedLock 을 쓰지
    // 않는 서비스까지 이 설정을 로드한다. 활성화는 @Import 로만 이뤄져야 한다.
    assertThat(AnnotationUtils.findAnnotation(ShedLockConfig.class, Component.class)).isNull();
    assertThat(AnnotationUtils.findAnnotation(ShedLockConfig.class, Configuration.class)).isNull();
  }

  /** 실제 서비스들이 쓰는 활성화 형태({@code @Import})를 그대로 재현한다. */
  @Configuration
  @Import(ShedLockConfig.class)
  static class ImportingConfig {}
}
