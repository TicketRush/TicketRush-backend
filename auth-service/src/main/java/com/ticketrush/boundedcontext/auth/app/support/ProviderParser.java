package com.ticketrush.boundedcontext.auth.app.support;

import com.ticketrush.boundedcontext.auth.domain.types.SocialProvider;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import org.springframework.stereotype.Component;

@Component
public class ProviderParser {

  public SocialProvider parse(String provider) {
    try {
      return SocialProvider.valueOf(provider.toUpperCase());
    } catch (Exception e) {
      throw new BusinessException(ErrorStatus.AUTH_PROVIDER_NOT_SUPPORT);
    }
  }
}
