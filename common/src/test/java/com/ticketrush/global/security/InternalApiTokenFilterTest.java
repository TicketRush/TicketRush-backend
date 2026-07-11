package com.ticketrush.global.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ticketrush.global.config.CustomSecurityProperties;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class InternalApiTokenFilterTest {

  private static final String PREFIX = "/api/v1/internal/user";
  private static final String INTERNAL_TOKEN = "test-internal-token";
  private static final String GATEWAY_TOKEN = "test-gateway-token";
  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  private InternalApiTokenFilter filter;

  @BeforeEach
  void setUp() {
    CustomSecurityProperties securityProperties = new CustomSecurityProperties();
    securityProperties.setInternalToken(INTERNAL_TOKEN);

    filter = new InternalApiTokenFilter(securityProperties, PREFIX);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("prefix와 정확히 일치하는 경로에는 필터를 적용한다")
  void exactPrefix_isFiltered() {
    MockHttpServletRequest request = request(PREFIX);

    assertFalse(filter.shouldNotFilter(request));
  }

  @Test
  @DisplayName("prefix 하위 경로에는 필터를 적용한다")
  void childPath_isFiltered() {
    MockHttpServletRequest request = request(PREFIX + "/1/auth-info");

    assertFalse(filter.shouldNotFilter(request));
  }

  @Test
  @DisplayName("prefix 문자열만 비슷한 경로에는 필터를 적용하지 않는다")
  void similarPrefix_isNotFiltered() {
    MockHttpServletRequest request = request(PREFIX + "X");

    assertTrue(filter.shouldNotFilter(request));
  }

  @Test
  @DisplayName("올바른 내부 API 토큰이면 ROLE_INTERNAL 인증을 생성하고 다음 필터를 실행한다")
  void validInternalToken_authenticatesAndContinues() throws Exception {
    MockHttpServletRequest request = request(PREFIX + "/1/auth-info");
    request.addHeader(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN);

    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainInvoked = new AtomicBoolean(false);
    FilterChain filterChain = (servletRequest, servletResponse) -> chainInvoked.set(true);

    filter.doFilter(request, response, filterChain);

    assertTrue(chainInvoked.get());
    assertEquals(200, response.getStatus());

    var authentication = SecurityContextHolder.getContext().getAuthentication();

    assertNotNull(authentication);
    assertEquals("internal-service", authentication.getPrincipal());
    assertTrue(authentication.isAuthenticated());
    assertTrue(
        authentication.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ROLE_INTERNAL")));
  }

  @Test
  @DisplayName("내부 API 토큰이 다르면 403을 반환하고 기존 SecurityContext를 제거한다")
  void invalidToken_returnsForbiddenAndClearsSecurityContext() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "existing-user", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

    MockHttpServletRequest request = request(PREFIX);
    request.addHeader(INTERNAL_TOKEN_HEADER, "invalid-token");

    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainInvoked = new AtomicBoolean(false);
    FilterChain filterChain = (servletRequest, servletResponse) -> chainInvoked.set(true);

    filter.doFilter(request, response, filterChain);

    assertEquals(403, response.getStatus());
    assertFalse(chainInvoked.get());
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  @DisplayName("gateway 토큰을 X-Internal-Token으로 보내도 403을 반환한다")
  void gatewayTokenInInternalHeader_returnsForbidden() throws Exception {
    MockHttpServletRequest request = request(PREFIX);
    request.addHeader(INTERNAL_TOKEN_HEADER, GATEWAY_TOKEN);

    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainInvoked = new AtomicBoolean(false);
    FilterChain filterChain = (servletRequest, servletResponse) -> chainInvoked.set(true);

    filter.doFilter(request, response, filterChain);

    assertEquals(403, response.getStatus());
    assertFalse(chainInvoked.get());
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  private MockHttpServletRequest request(String requestUri) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI(requestUri);
    return request;
  }
}
