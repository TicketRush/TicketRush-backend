package com.ticketrush.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * payment-service 의 스케줄링 활성화 설정 (#574).
 *
 * <p>booking·seat·performance 도 같은 이름의 설정에서 {@link EnableScheduling} 을 켜지만, 그쪽은 common 의 {@code
 * ShedLockConfig} 를 {@code @Import} 해 분산 락까지 함께 활성화한다(#439). payment 는 ShedLock 을 쓰지 않으므로(근거는
 * {@code ReplayRefundFailureSignalScheduler} javadoc 과 ADR 0014) 그 {@code @Import} 없이 이 설정만 둔다.
 * payment 에 스케줄러가 생긴 것은 #574 가 처음이다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
