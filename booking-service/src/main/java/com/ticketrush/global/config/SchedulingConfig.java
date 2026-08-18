package com.ticketrush.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * booking-service 의 스케줄링·분산 락 활성화 설정 (#439).
 *
 * <p>락 설정 본체는 common 의 {@link ShedLockConfig} 에 있다. 그 클래스는 {@code @Configuration} 이 아니라 컴포넌트 스캔에
 * 잡히지 않으므로, 락이 필요한 이 서비스가 {@code @Import} 로 직접 활성화한다. 이 {@code @Import} 가 빠지면 스케줄러는 계속 돌지만
 * {@code @SchedulerLock} 이 무효가 되어 여러 인스턴스에서 같은 작업이 중복 실행된다.
 */
@Configuration
@EnableScheduling
@Import(ShedLockConfig.class)
public class SchedulingConfig {}
