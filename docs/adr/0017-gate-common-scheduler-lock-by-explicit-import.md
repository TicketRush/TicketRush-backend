# 17. common의 스케줄러 락 설정은 조건 프로퍼티가 아니라 `@Import`로 게이트한다

날짜: 2026-08-19

## 상태

승인됨

## 맥락

`ShedLockConfig`가 booking·seat·performance 세 모듈에 **동일 FQN(`com.ticketrush.global.config.ShedLockConfig`)으로 복붙**돼 있었다([#439](https://github.com/TicketRush/TicketRush-backend/issues/439)). 세 파일의 실질 차이는 `@Value("${spring.application.name:모듈명}")`의 기본값 문자열 한 줄뿐이었고, 그 기본값조차 세 모듈 모두 `application.yml`에 이름을 정의해 둬 **도달하지 않는 죽은 코드**였다.

common으로 옮기는 것 자체는 단순하다. 문제는 **common의 `@Configuration`이 전 서비스에 컴포넌트 스캔된다**는 것이다. 여덟 개 서비스가 모두 `package com.ticketrush`에 `@SpringBootApplication`을 두고 있어, `com.ticketrush.global.*`은 별도 선언 없이 전부 스캔 대상이 된다(`common/.../RedisConfig.java` 주석이 이 사실을 명시한다).

그대로 옮기면 ShedLock이 필요 없는 네 서비스(payment·user·ticket·auth)까지 이 설정을 로드한다. 특히 payment·user·ticket은 `DataRedisAutoConfiguration`을 **명시적으로 exclude**해 둔 서비스다. 그 결정은 [ADR 14](0014-recover-refund-failure-signal-by-reconciliation.md)·[ADR 15](0015-recover-charged-expired-booking-by-auto-refund.md)가 "payment를 Redis 장애 범위에 넣지 않는다"로 못 박은 것이라, 이번 승격이 그것을 되돌려서는 안 된다.

### 기존 선례로는 충분하지 않았다

레포에는 이미 같은 문제를 푼 선례가 있다 — `RedisConfig`의 `@ConditionalOnProperty(prefix = "spring.data.redis", name = "host")`다. 스캔은 되되 조건으로 꺼지는 방식이다. 그러나 ShedLock에 그대로 적용하면 세 곳에서 어긋난다.

1. **auth가 새어 나간다.** auth는 `spring.data.redis.host`를 갖고 있고(`application-local.yml`·`application-prod.yml`) `DataRedisAutoConfiguration`을 exclude하지도 않는다. 스케줄러가 하나도 없는데 `@EnableScheduling`과 `lockProvider` 빈이 의도 없이 켜진다. "Redis를 쓴다"와 "ShedLock을 쓴다"는 다른 조건인데 전자로 후자를 근사한 결과다.
2. **테스트가 조용히 회귀한다.** `application-test.yml` 어디에도 `spring.data.redis.host`가 없다. 게이트를 붙이는 순간 performance의 실앱 컨텍스트 테스트 열한 건에서 `ShedLockConfig`가 사라지고, `@EnableScheduling`까지 함께 소실된다.
3. **prod 환경변수 사고가 무증상이 된다.** prod는 `${REDIS_HOST}`(기본값 없음)이다. 주입에 실패하면 설정이 통째로 비활성화되어 아홉 개 스케줄러가 **에러 없이 전부 정지**한다. 지금 구조에서는 같은 사고가 Redis 커넥션 실패로 시끄럽게 드러난다.

이슈 본문이 제안했던 `@ConditionalOnClass`(shedlock)/`@ConditionalOnBean`(RedisConnectionFactory)도 성립하지 않는다. 이 레포는 `AutoConfiguration.imports`가 한 건도 없고 모든 common 설정이 **일반 컴포넌트 스캔**으로 올라오는데, `@ConditionalOnBean` 계열은 빈 정의 등록 순서에 의존해 자동설정 단계에서만 순서가 보장된다. 팀은 이미 이 위험을 문서화해 뒀다 — `NotifierConfig` javadoc의 *"`@ConditionalOnMissingBean`을 사용하면 스캔 순서에 따라 두 빈이 동시에 등록될 수 있어 사용하지 않는다"*. 그리고 `@ConditionalOnClass`는 애초에 판정 불가다. common이 shedlock을 의존하는 순간 jar가 **전 서비스 runtimeClasspath에 전파**되어 조건이 항상 참이 된다.

## 결정

**`ShedLockConfig`에 `@Configuration`을 붙이지 않는다. 락이 필요한 서비스가 각자 `@Import(ShedLockConfig.class)`로 명시 활성화한다.**

```java
// common — 스캔 대상이 아니다
@EnableSchedulerLock(defaultLockAtMostFor = "50s")
public class ShedLockConfig { ... }

// booking / seat / performance — 각자의 global/config/SchedulingConfig
@Configuration
@EnableScheduling
@Import(ShedLockConfig.class)
public class SchedulingConfig {}
```

`@Configuration`(메타 `@Component`)이 없으면 컴포넌트 스캔 후보에서 아예 빠지고, `@Import`된 클래스는 lite configuration으로 처리되어 `@Bean`과 `@EnableSchedulerLock`이 정상 동작한다. 이 동작은 테스트로 실증했다(`ShedLockConfigTest`).

`@EnableSchedulerLock`은 common 쪽에 둔다. 모듈로 내리면 `defaultLockAtMostFor` 값이 세 곳으로 복제되어 이 이슈가 없애려던 drift가 되살아난다. 반대로 `@EnableScheduling`은 모듈 쪽에 둔다. payment가 이미 같은 이름·같은 자리에서 스케줄링만 켜고 있어(#574) 네 서비스의 형태가 대칭이 된다.

### 왜 프로퍼티 게이트가 아닌가

`app.shedlock.enabled` 같은 신규 프로퍼티도 위 세 문제를 모두 피한다. 그럼에도 `@Import`를 택한 이유는 **활성화 지점이 설정이 아니라 코드에 있기 때문**이다.

- 누가 락을 쓰는지가 `grep @Import(ShedLockConfig` 한 번으로 드러난다. 프로퍼티는 여덟 개 yml을 읽어야 알 수 있다.
- 프로퍼티 게이트에서 값이 빠지면 `@EnableScheduling`까지 함께 죽어 **그 서비스의 스케줄러가 전부 무증상 정지**한다. 우회 스위치가 존재한다는 것 자체가 운영 중 잘못 꺼질 표면이다.
- 배제 대상 네 서비스에 아무 설정도 추가하지 않는다. payment·user·ticket·auth는 이 변경으로 **단 한 줄도 바뀌지 않는다**.

## 결과

- ShedLock 설정이 common 한 곳에만 존재한다. shedlock 버전 하드코딩도 세 곳에서 한 곳(`common/build.gradle`)으로 줄었다.
- 락 키(`job-lock:{applicationName}-{profile}:{lockName}`)는 문자 단위로 불변이다. 무중단 배포 중 구/신 인스턴스가 같은 키를 잡는다.
- **`@Import`를 빠뜨리면 락이 조용히 무효가 된다.** `@EnableScheduling`은 서비스 쪽에 있으므로 스케줄러는 계속 돌고, 여러 인스턴스에서 중복 실행된다. `shedlock-spring`이 common의 `api` 의존이라 `@SchedulerLock`은 어느 서비스에서든 컴파일되므로 컴파일 에러도 나지 않는다. 이 계약을 `docs/kafka-event-guide.md`와 `docs/backend-convention.md`에 명시했다.
- `spring.application.name`이 비어 있으면 기동을 실패시킨다. 중립적 기본값을 두면 이름이 빠진 서비스끼리 같은 락 네임스페이스를 공유해 서로의 락을 가져가는데, 증상이 "스케줄러가 가끔 안 돈다"로만 나타나 원인 추적이 어렵다.
- common 설정을 게이트하는 방법이 두 가지가 됐다. **런타임 상태에 따라 꺼져야 하면 `RedisConfig`처럼 `@ConditionalOnProperty`, 사용 여부가 서비스마다 고정이면 `@Import`**를 쓴다.
