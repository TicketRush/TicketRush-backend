package com.ticketrush.boundedcontext.auth.app.usecase;

import com.ticketrush.boundedcontext.auth.domain.types.SocialProvider;
import com.ticketrush.boundedcontext.auth.out.apiclient.SocialOauthApiClient;
import com.ticketrush.boundedcontext.auth.out.apiclient.SocialOauthApiClientFactory;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.security.AuthSecurityProperties;
import com.ticketrush.global.status.ErrorStatus;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OauthLoginUrlUseCase {

  private final SocialOauthApiClientFactory socialOauthApiClientFactory;
  private final AuthSecurityProperties securityProperties;

  // 어느 provider로 로그인할 지를 받아서 그 로그인에 필요한 OAuth 인증 URL을 만들어 반환
  public String generateOAuthUrl(SocialProvider provider) {

    SocialOauthApiClient oauthClient = socialOauthApiClientFactory.getClient(provider);

    String redirectUri = oauthClient.getDefaultRedirectUri();

    validateRedirectUri(redirectUri);

    return oauthClient.generateOAuthUrl();
  }

  private void validateRedirectUri(String redirectUri) {

    if (redirectUri == null || redirectUri.isBlank()) {
      throw new BusinessException(ErrorStatus.AUTH_OAUTH_INVALID_REDIRECT_URI);
    }

    List<String> allowedRedirectDomains = securityProperties.getAllowedRedirectDomains();

    if (allowedRedirectDomains == null || allowedRedirectDomains.isEmpty()) {
      throw new BusinessException(ErrorStatus.AUTH_OAUTH_INVALID_REDIRECT_URI);
    }

    URI uri;
    try {
      uri = new URI(redirectUri);
    } catch (URISyntaxException e) {
      throw new BusinessException(ErrorStatus.AUTH_OAUTH_INVALID_REDIRECT_URI);
    }

    String host = uri.getHost();

    if (host == null || host.isBlank()) {
      throw new BusinessException(ErrorStatus.AUTH_OAUTH_INVALID_REDIRECT_URI);
    }

    boolean isAllowed =
        allowedRedirectDomains.stream()
            .anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));

    if (!isAllowed) {
      throw new BusinessException(ErrorStatus.AUTH_OAUTH_INVALID_REDIRECT_URI);
    }
  }
}
