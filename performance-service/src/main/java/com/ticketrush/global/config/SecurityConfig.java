package com.ticketrush.global.config;

import com.ticketrush.global.filter.GatewayHeaderFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
        // 필터 순서 주의: gatewayHeaderFilter가 internalApiTokenFilter보다 먼저 실행돼야 한다.
        // 현재는 두 필터가 동일 시크릿(GATEWAY_INTERNAL_TOKEN)을 공유해 gateway가 clear하지 않으므로 안전하나,
        // 향후 custom.security.internal-token을 gateway.internal-token과 분리하면 gatewayHeaderFilter가
        // 토큰 불일치 시 SecurityContext를 clear한다. 그때 순서가 뒤집히면 internalApiTokenFilter가 세팅한
        // ROLE_INTERNAL이 지워져 내부 호출이 403이 되므로, 이 순서를 반드시 유지한다.
        .addFilterBefore(gatewayHeaderFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(internalApiTokenFilter, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/api/v1/internal/performance/**")
                    .hasRole("INTERNAL")
                    .requestMatchers("/api/v1/performance/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .permitAll());

    return http.build();
  }
}
