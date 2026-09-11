package com.ticketrush.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/**
 * payment-service의 RestClient 빈이 셋(toss·ticket·booking)이라 주입은 타입이 아니라 <b>이름</b>으로 갈린다. 이름이 어긋나면
 * booking 대신 ticket 주소로 예매를 조회하게 되는데, {@code PaymentServiceApplicationTests} 의 {@code contextLoads}
 * 는 빈이 뜨는 것만 보고 <b>어느 빈이 어디로 들어갔는지는 보지 않아</b> 이것을 잡지 못한다(#490 셀프 리뷰 이월분, #571).
 *
 * <p>빈 URL fail-fast 분기도 여기서만 검증된다. {@code application-test.yml} 에 {@code service.*} 가 없어 base 의
 * 기본값이 들어가므로, 전체 컨텍스트 테스트로는 그 분기가 절대 실행되지 않는다.
 */
class RestClientConfigTest {

  private static final String TICKET_URL = "http://localhost:8087";
  private static final String BOOKING_URL = "http://localhost:8084";

  /**
   * toss 빈까지 켜서 RestClient 후보를 셋으로 만든다. 실제 prod 구성이 그렇고, 후보가 하나뿐이면 타입으로도 주입이 되어 <b>이름 검증이
   * 무의미해진다.</b>
   */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(RestClientConfig.class)
          .withPropertyValues(
              "service.ticket.url=" + TICKET_URL,
              "service.booking.url=" + BOOKING_URL,
              "payment.pg.toss.enabled=true",
              "payment.pg.toss.base-url=https://api.tosspayments.com",
              "payment.pg.toss.secret-key=test_sk_dummy");

  @Test
  @DisplayName("성공: 서비스별 RestClient 빈 셋이 모두 생성되고 서로 다른 인스턴스다")
  void creates_all_rest_client_beans() {
    contextRunner.run(
        context -> {
          assertThat(context)
              .hasNotFailed()
              .hasBean("tossPaymentRestClient")
              .hasBean("ticketServiceRestClient")
              .hasBean("bookingServiceRestClient");

          // 타임아웃 예산이 대상별로 갈려 있으므로(ticket 1s/2s · booking 1s/1s) 인스턴스가 공유되면
          // 그 분리가 성립하지 않는다.
          assertThat(context.getBean("ticketServiceRestClient", RestClient.class))
              .isNotSameAs(context.getBean("bookingServiceRestClient", RestClient.class));
        });
  }

  @Test
  @DisplayName("성공: RestClient 후보가 셋이어도 이름으로 갈려 주입된다")
  void injects_rest_client_by_bean_name() {
    contextRunner
        .withUserConfiguration(BookingClientHolder.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(BookingClientHolder.class);

              // 생성자 파라미터 이름으로 해소되므로 -parameters 컴파일 옵션에 의존한다. 옵션이 빠지면
              // 후보 셋 중 아무거나 들어가고, 그 결과는 booking 대신 다른 서비스 주소로의 조회다.
              assertThat(context.getBean(BookingClientHolder.class).restClient())
                  .isSameAs(context.getBean("bookingServiceRestClient", RestClient.class));
            });
  }

  @Test
  @DisplayName("실패: service.booking.url 이 비면 기동이 실패한다 — fail-closed 경로가 조용히 새는 것보다 낫다")
  void fails_to_start_when_booking_url_is_blank() {
    contextRunner
        .withPropertyValues("service.booking.url=")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BOOKING_SERVICE_URL"));
  }

  @Test
  @DisplayName("성공: toss 가 꺼져 있으면 toss 빈만 빠지고 나머지 둘은 뜬다 — test 프로파일의 실제 구성이다")
  void toss_client_is_conditional() {
    contextRunner
        .withPropertyValues("payment.pg.toss.enabled=false")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean("tossPaymentRestClient")
                    .hasBean("ticketServiceRestClient")
                    .hasBean("bookingServiceRestClient"));
  }

  /** 이름 기반 주입을 실제 주입으로 확인하기 위한 소비자. {@code BookingRestClient} 가 하는 것과 같은 방식이다. */
  static class BookingClientHolder {

    private final RestClient restClient;

    BookingClientHolder(RestClient bookingServiceRestClient) {
      this.restClient = bookingServiceRestClient;
    }

    RestClient restClient() {
      return restClient;
    }
  }
}
