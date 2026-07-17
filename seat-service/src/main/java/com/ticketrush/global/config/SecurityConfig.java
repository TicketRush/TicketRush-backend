package com.ticketrush.global.config;

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

  private static final String INTERNAL_SEAT_API_PREFIX = "/api/v1/internal/seat";

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
        .addFilterBefore(internalApiTokenFilter, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    // TODO: 인증 이후 허용 범위 수정
                    .permitAll()
                    .requestMatchers("/api/v1/internal/seat/**")
                    .hasRole("INTERNAL")
                    .anyRequest()
                    .permitAll());

    return http.build();
  }
}
