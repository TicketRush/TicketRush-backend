package com.ticketrush.boundedcontext.payment.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.config.RestClientConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/**
 * Toss 클라이언트가 <b>실제로 스프링 컨텍스트에서 생성되는지</b> 고정한다 (#657).
 *
 * <p>세 클라이언트는 {@code payment.pg.toss.enabled=true} 일 때만 등록되는데, 운영에서 그 값이 참이었던 적이 없어 생성자 의존성이 한 번도
 * 검증되지 않았다. 그 사이 Jackson 2 {@code ObjectMapper} 를 주입받는 코드가 남아 있었고, Spring Boot 4 는 그 타입 빈을 등록하지
 * 않으므로 <b>운영에서 켜는 순간 payment-service 가 통째로 기동 실패</b>했다.
 *
 * <p>기존 테스트로는 잡히지 않았다. 클라이언트 단위 테스트 셋은 생성자를 직접 호출해 컨텍스트를 거치지 않고, {@code
 * PaymentServiceApplicationTests.contextLoads} 는 {@code application-test.yml} 에 toss 설정이 없어 이 빈들을
 * 아예 띄우지 않으며, {@code RestClientConfigTest} 는 toss 를 켜지만 {@code RestClientConfig} 만 등록해
 * {@code @Component} 인 클라이언트가 컨텍스트에 들어오지 않는다.
 *
 * <p><b>검증 범위는 "조건 평가 + 생성자 의존성 해소"다.</b> 클라이언트를 명시 등록하므로 {@code @Component} 를 떼거나 스캔 대상에서 빠지는 회귀는
 * 여기서 잡히지 않는다.
 */
class TossPaymentClientBeanCreationTest {

  /**
   * {@code RestClientConfig} 전체를 넣어 RestClient 후보를 셋으로 만든다. 기동 실패는 {@code
   * tossPaymentApprovalClient} 생성 중에 났고 그 생성자가 {@code @Qualifier("tossPaymentRestClient")} 로 갈리므로,
   * 이름 해소까지 함께 태우는 쪽이 사고를 재현한다.
   *
   * <p>toss 로 켜지는 클라이언트 <b>셋을 모두</b> 등록한다. Inquiry 는 지금 매퍼를 주입받지 않아 안전하지만, 목록에서 빠뜨리면 "toss 를 켜도
   * 기동한다"는 이 파일의 주장에 구멍이 생긴다.
   */
  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        // Cancel 클라이언트 생성자의 @Value placeholder 를 해소한다.
        .withBean(
            PropertySourcesPlaceholderConfigurer.class, PropertySourcesPlaceholderConfigurer::new)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withUserConfiguration(
            RestClientConfig.class,
            TossPaymentApprovalClient.class,
            TossPaymentCancelClient.class,
            TossPaymentInquiryClient.class)
        .withPropertyValues(
            "service.ticket.url=http://localhost:8087",
            "service.booking.url=http://localhost:8084",
            "payment.pg.toss.enabled=true",
            "payment.pg.toss.base-url=https://api.tosspayments.com",
            "payment.pg.toss.secret-key=test_sk_dummy");
  }

  /**
   * 이 테스트가 이 파일의 존재 이유다.
   *
   * <p>Jackson 자동구성을 <b>일부러 넣지 않는다.</b> "Jackson 빈이 있어서 뜬다"가 아니라 "<b>Jackson 빈이 없어도 뜬다</b>"를 단언해야
   * 누군가 매퍼를 다시 생성자 파라미터로 되돌렸을 때 즉시 빨개진다. 전자로 적으면 주입 타입이 맞는 동안에만 통과하므로, Boot 가 또 기본 매퍼 타입을 바꾸면 같은
   * 사고를 그대로 반복한다.
   */
  @Test
  @DisplayName("성공: Toss 클라이언트는 Jackson 빈에 의존하지 않고 생성된다")
  void creates_all_clients_without_any_jackson_bean() {
    runner()
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(TossPaymentApprovalClient.class)
                    .hasSingleBean(TossPaymentCancelClient.class)
                    .hasSingleBean(TossPaymentInquiryClient.class));
  }

  /** 운영과 같은 Jackson 구성(전역 snake_case 커스터마이저 포함)에서도 생성되는지 확인한다. */
  @Test
  @DisplayName("성공: 운영과 같은 Jackson 구성에서도 Toss 클라이언트가 생성된다")
  void creates_all_clients_under_production_like_jackson_setup() {
    runner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withUserConfiguration(JacksonConfig.class)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(TossPaymentApprovalClient.class)
                    .hasSingleBean(TossPaymentCancelClient.class)
                    .hasSingleBean(TossPaymentInquiryClient.class));
  }

  @Test
  @DisplayName("성공: toss 가 꺼져 있으면 세 클라이언트 모두 등록되지 않는다")
  void registers_no_client_when_toss_is_disabled() {
    runner()
        .withPropertyValues("payment.pg.toss.enabled=false")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(TossPaymentApprovalClient.class)
                    .doesNotHaveBean(TossPaymentCancelClient.class)
                    .doesNotHaveBean(TossPaymentInquiryClient.class));
  }
}
