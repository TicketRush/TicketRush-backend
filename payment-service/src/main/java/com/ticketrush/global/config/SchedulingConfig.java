package com.ticketrush.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * payment-service 의 스케줄링 활성화 설정 (#574).
 *
 * <p>다른 서비스(booking·seat·performance)는 {@code ShedLockConfig} 가 {@link EnableScheduling} 을 겸하지만
 * payment 는 ShedLock 을 쓰지 않으므로(근거는 {@code ReplayRefundFailureSignalScheduler} javadoc 과 ADR 0014) 이
 * 설정만 따로 둔다. payment 에 스케줄러가 생긴 것은 이 이슈가 처음이다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
