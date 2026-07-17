package com.ticketrush.global.filter;

import com.ticketrush.global.security.CustomUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class GatewayHeaderFilter extends OncePerRequestFilter {

  private static final String USER_ID_HEADER = "X-User-Id";
  private static final String USER_ROLE_HEADER = "X-User-Role";
  private static final String GATEWAY_TOKEN_HEADER = "X-Gateway-Token";

  @Value("${gateway.internal-token}")
  private String gatewayToken;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String gatewayTokenHeader = request.getHeader(GATEWAY_TOKEN_HEADER);

    // Gateway 토큰 검증 실패 시 사용자 인증 무효
    if (!gatewayToken.equals(gatewayTokenHeader)) {
      SecurityContextHolder.clearContext();
      filterChain.doFilter(request, response);
      return;
    }

    String userIdHeader = request.getHeader(USER_ID_HEADER);
    String roleHeader = request.getHeader(USER_ROLE_HEADER);

    // 사용자 식별 헤더가 모두 존재할 때만 인증 처리
    if (userIdHeader != null && roleHeader != null) {
      try {
        Long userId = Long.valueOf(userIdHeader);

        List<SimpleGrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_" + roleHeader));

        CustomUserDetails principal = new CustomUserDetails(userId, roleHeader);

        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(principal, null, authorities);

        SecurityContextHolder.getContext().setAuthentication(authentication);
      } catch (NumberFormatException e) {
        SecurityContextHolder.clearContext();
      }
    }

    filterChain.doFilter(request, response);
  }
}
