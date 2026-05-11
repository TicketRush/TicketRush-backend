package com.ticketrush.boundedcontext.auth.out.apiclient;

import com.ticketrush.boundedcontext.auth.domain.types.SocialProvider;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 여러 OAuth 서비스 구현체들 중에서 provider에 맞는 것을 찾아 반환하는 역할
@Component
@RequiredArgsConstructor
public class SocialOauthApiClientFactory {

  private final List<SocialOauthApiClient> apiClients;

  public SocialOauthApiClient getClient(SocialProvider provider) {

    return apiClients.stream()
        .filter(client -> client.getProvider() == provider)
        .findFirst()
        .orElseThrow(() -> new BusinessException(ErrorStatus.AUTH_PROVIDER_NOT_SUPPORT));
  }
}
