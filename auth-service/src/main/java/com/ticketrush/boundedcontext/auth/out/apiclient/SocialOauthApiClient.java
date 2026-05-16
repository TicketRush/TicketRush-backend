package com.ticketrush.boundedcontext.auth.out.apiclient;

import com.ticketrush.boundedcontext.auth.domain.types.SocialProvider;
import com.ticketrush.boundedcontext.auth.domain.types.SocialUserInfo;

public interface SocialOauthApiClient {

  SocialProvider getProvider();

  SocialUserInfo getUserInfo(String code);

  String generateOAuthUrl();

  String getDefaultRedirectUri();
}
