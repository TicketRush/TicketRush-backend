package com.ticketrush.global.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// application.yml에 있는 값을 Java 객체로 가져오는 역할
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "custom.security")
public class CustomSecurityProperties {

  private String internalToken;

  private boolean permitAll;
  private List<String> permitUrls = new ArrayList<>();
}
