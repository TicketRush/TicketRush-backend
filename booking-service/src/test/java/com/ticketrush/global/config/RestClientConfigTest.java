package com.ticketrush.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.booking.out.apiclient.SeatRestClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/**
 * RestClient 빈이 4개(seat 쓰기·seat 조회·ticket·performance)라 주입은 타입이 아니라 <b>이름</b>으로 갈린다. 특히 {@link
 * SeatRestClient}는 한 생성자에 RestClient를 둘 받으므로, 이름이 어긋나면 애플리케이션이 기동 시점에 죽는다 — 기존 {@code contextLoads}
 * 테스트는 실제 설정을 로드하지 않아 이것을 잡지 못한다.
 */
class RestClientConfigTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(RestClientConfig.class, CustomSecurityProperties.class)
          .withPropertyValues(
              "service.seat.url=http://localhost:8086",
              "service.ticket.url=http://localhost:8087",
              "service.performance.url=http://localhost:8083");

  @Test
  @DisplayName("성공: 서비스별 RestClient 빈이 모두 생성된다")
  void creates_all_rest_client_beans() {
    contextRunner.run(
        context ->
            assertThat(context)
                .hasNotFailed()
                .hasBean("seatServiceRestClient")
                .hasBean("seatQueryRestClient")
                .hasBean("ticketServiceRestClient")
                .hasBean("performanceServiceRestClient"));
  }

  @Test
  @DisplayName("성공: RestClient 후보가 여럿이어도 SeatRestClient의 두 클라이언트가 이름으로 갈려 주입된다")
  void injects_distinct_seat_clients_by_name() {
    contextRunner
        .withUserConfiguration(SeatRestClient.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(SeatRestClient.class);
              // 쓰기용(3s/10s)과 조회용(1s/2s)이 서로 다른 인스턴스여야 예산 분리가 실제로 성립한다.
              assertThat(context.getBean("seatServiceRestClient", RestClient.class))
                  .isNotSameAs(context.getBean("seatQueryRestClient", RestClient.class));
            });
  }
}
