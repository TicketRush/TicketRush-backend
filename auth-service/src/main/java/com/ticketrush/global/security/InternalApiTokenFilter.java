package com.ticketrush.global.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.ticketrush.global.config.CustomSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class InternalApiTokenFilter extends OncePerRequestFilter {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
  private static final String INTERNAL_AUTH_API_PREFIX = "/api/v1/internal/auth";

  private final CustomSecurityProperties securityProperties;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !isInternalAuthApi(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String expectedToken = securityProperties.getInternalToken();
    String actualToken = request.getHeader(INTERNAL_TOKEN_HEADER);

    if (!StringUtils.hasText(expectedToken)
        || !StringUtils.hasText(actualToken)
        || !MessageDigest.isEqual(expectedToken.getBytes(UTF_8), actualToken.getBytes(UTF_8))) {
      SecurityContextHolder.clearContext();
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      return;
    }

    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));

    SecurityContextHolder.getContext().setAuthentication(authentication);

    filterChain.doFilter(request, response);
  }

  private boolean isInternalAuthApi(HttpServletRequest request) {
    String path = request.getRequestURI();

    return INTERNAL_AUTH_API_PREFIX.equals(path) || path.startsWith(INTERNAL_AUTH_API_PREFIX + "/");
  }
}
