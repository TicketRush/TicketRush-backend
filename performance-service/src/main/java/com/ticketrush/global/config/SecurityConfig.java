package com.ticketrush.global.config;

import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.global.security.InternalApiTokenFilter;
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

  private static final String INTERNAL_PERFORMANCE_API_PREFIX = "/api/v1/internal/performance";

  private final GatewayHeaderFilter gatewayHeaderFilter;

  @Bean
  public InternalApiTokenFilter internalApiTokenFilter(
      CustomSecurityProperties securityProperties) {
    return new InternalApiTokenFilter(securityProperties, INTERNAL_PERFORMANCE_API_PREFIX);
  }

  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http, InternalApiTokenFilter internalApiTokenFilter) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(cors -> {})
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
