package com.ticketrush.global.config;

import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.global.security.InternalApiTokenFilter;
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

  private static final String INTERNAL_BOOKING_API_PREFIX = "/api/v1/internal/booking";

  private final GatewayHeaderFilter gatewayHeaderFilter;

  @Bean
  public InternalApiTokenFilter internalApiTokenFilter(
    CustomSecurityProperties securityProperties) {
    return new InternalApiTokenFilter(securityProperties, INTERNAL_BOOKING_API_PREFIX);
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
            .requestMatchers("/api/v1/booking/admin/**")
            .hasRole("ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/v1/booking")
            .authenticated()
            .anyRequest()
            .permitAll());

    return http.build();
  }
}