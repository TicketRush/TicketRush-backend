package com.ticketrush.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "custom.cors")
public record CorsProperties(boolean enabled, List<String> allowedOrigins) {
  public CorsProperties {
    if (allowedOrigins == null) {
      allowedOrigins = List.of();
    }
  }

  public boolean isCorsEnabled() {
    return enabled && !allowedOrigins.isEmpty();
  }
}
