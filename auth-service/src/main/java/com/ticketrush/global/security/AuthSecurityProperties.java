package com.ticketrush.global.security;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "custom.security.oauth2")
public class AuthSecurityProperties {

  private List<String> allowedRedirectDomains = new ArrayList<>();
}
