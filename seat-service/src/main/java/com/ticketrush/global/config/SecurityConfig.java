package com.ticketrush.global.config;

import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.global.security.InternalApiTokenFilter;
import com.ticketrush.global.status.ErrorStatus;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

  private static final String UNAUTHORIZED_RESPONSE_TEMPLATE =
      "{\"is_success\":false,\"code\":\"%s\",\"message\":\"%s\"}";

  private static final String INTERNAL_SEAT_API_PREFIX = "/api/v1/internal/seat";

  /*
   * seat-service의 첫 사용자 인증 경계다(#562). 이 필터를 체인에 넣기 전까지 seat는 게이트웨이가 주입하는
   * X-User-Id / X-User-Role을 Authentication으로 바꾸지 않았다.
   *
   * 빈 주입만으로는 부족하다 — GatewayHeaderFilter는 common의 @Component라 컨텍스트에는 이미 올라와 있었지만,
   * Boot의 Filter 빈 자동 등록은 기본 order가 LOWEST_PRECEDENCE라 springSecurityFilterChain(-100) '뒤'에 돈다.
   * 그러면 인가 판정 시점에 SecurityContext가 비어 있어 hasRole("ADMIN")이 익명 사용자를 만나 항상 거절한다.
   */
  private final GatewayHeaderFilter gatewayHeaderFilter;

  @Bean
  public InternalApiTokenFilter internalApiTokenFilter(
      CustomSecurityProperties securityProperties) {
    return new InternalApiTokenFilter(securityProperties, INTERNAL_SEAT_API_PREFIX);
  }

  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http, InternalApiTokenFilter internalApiTokenFilter) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(cors -> {})
        .httpBasic(httpBasic -> httpBasic.disable())
        .formLogin(formLogin -> formLogin.disable())
        .addFilterBefore(gatewayHeaderFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(internalApiTokenFilter, UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(
            exception ->
                exception.authenticationEntryPoint(
                    (request, response, authException) -> {
                      response.setStatus(ErrorStatus.UNAUTHORIZED.getHttpStatus().value());
                      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                      // 지정하지 않으면 서블릿 기본값 ISO-8859-1로 나가 한글 메시지가 '?'로 파괴된다(#560).
                      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                      response
                          .getWriter()
                          .write(
                              UNAUTHORIZED_RESPONSE_TEMPLATE.formatted(
                                  ErrorStatus.UNAUTHORIZED.getCode(),
                                  ErrorStatus.UNAUTHORIZED.getMessage()));
                    }))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    // TODO: 인증 이후 허용 범위 수정
                    .permitAll()
                    .requestMatchers("/api/v1/internal/seat/**")
                    .hasRole("INTERNAL")
                    // 관리자 네임스페이스는 광범위 permitAll보다 앞에 와야 선점된다 (#562).
                    .requestMatchers("/api/v1/seat/admin/**")
                    .hasRole("ADMIN")
                    // 나머지 좌석 조회는 예매 화면이 비로그인으로도 보는 공개 API다.
                    .anyRequest()
                    .permitAll());

    return http.build();
  }
}
