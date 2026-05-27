package com.ticketrush.global.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "custom.security")
public class SecurityProperties {

  private String internalToken;

  private boolean permitAll;

  private List<String> permitUrls;
}
