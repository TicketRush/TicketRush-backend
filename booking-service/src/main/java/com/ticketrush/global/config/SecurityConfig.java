package com.ticketrush.global.config;

import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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

  private final GatewayHeaderFilter gatewayHeaderFilter;
  private final InternalApiTokenFilter internalApiTokenFilter;

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(cors -> {})
        .httpBasic(httpBasic -> httpBasic.disable())
        .formLogin(formLogin -> formLogin.disable())
        // 필터 순서 주의: gatewayHeaderFilter가 internalApiTokenFilter보다 먼저 실행돼야 한다.
        // gatewayHeaderFilter는 토큰 불일치 시 SecurityContext를 clear하므로, 순서가 뒤집히면
        // internalApiTokenFilter가 세팅한 ROLE_INTERNAL이 지워져 내부 호출이 403이 된다.
        // (두 addFilterBefore가 같은 기준 필터를 쓰지만 정렬이 stable하므로 등록 순서가 보존된다.)
        // 두 키를 분리하려면 필터 순서만으로는 부족하다: 내부 호출의 송신 측(SeatRestClient 등)이
        // @Value("${gateway.internal-token}")을 헤더에 싣는 반면 이 필터는 custom.security.internal-token으로
        // 검증하므로, 분리 시 송신 측 헤더 소스도 함께 바꾸지 않으면 모든 내부 호출이 403이 된다.
        // 지금은 두 키가 같은 GATEWAY_INTERNAL_TOKEN 시크릿으로 해석돼 일치한다.
        .addFilterBefore(gatewayHeaderFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(internalApiTokenFilter, UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(
            exception ->
                exception.authenticationEntryPoint(
                    (request, response, authException) -> {
                      response.setStatus(ErrorStatus.UNAUTHORIZED.getHttpStatus().value());
                      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                      response
                          .getWriter()
                          .write(
                              """
                              {"is_success":false,"code":"%s","message":"%s"}
                              """
                                  .formatted(
                                      ErrorStatus.UNAUTHORIZED.getCode(),
                                      ErrorStatus.UNAUTHORIZED.getMessage()));
                    }))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    // TODO: 인증 이후 허용 범위 수정
                    .permitAll()
                    .requestMatchers("/api/v1/internal/booking/**")
                    .hasRole("INTERNAL")
                    .requestMatchers(HttpMethod.POST, "/api/v1/booking")
                    .authenticated()
                    .anyRequest()
                    .permitAll());

    return http.build();
  }
}
