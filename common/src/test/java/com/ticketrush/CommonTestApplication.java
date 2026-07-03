package com.ticketrush;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * common 모듈 슬라이스 테스트(@DataJpaTest 등)용 부트스트랩 설정.
 *
 * <p>common은 실행형 애플리케이션이 아니라 라이브러리라 {@code @SpringBootApplication}이 없다. 슬라이스 테스트가
 * {@code @SpringBootConfiguration}을 찾을 수 있도록 테스트 전용으로만 둔다.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class CommonTestApplication {}
