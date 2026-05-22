package com.ticketrush.global.security;

import com.ticketrush.global.config.SecurityProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "custom.security")
public class AuthSecurityProperties extends SecurityProperties {

  private String internalToken;

  private Oauth2 oauth2 = new Oauth2();

  @Getter
  @Setter
  public static class Oauth2 {
    private List<String> allowedRedirectDomains = new ArrayList<>();
  }
}
