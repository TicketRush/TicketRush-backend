package com.ticketrush.global.config;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.mail.autoconfigure.MailProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
@RequiredArgsConstructor
public class MailConfig {

  private static final int DEFAULT_SMTP_PORT = 587;

  private final MailProperties mailProperties;

  @Bean
  public JavaMailSender javaMailSender() {
    JavaMailSenderImpl mailSender = new JavaMailSenderImpl();

    mailSender.setHost(mailProperties.getHost());
    mailSender.setPort(Objects.requireNonNullElse(mailProperties.getPort(), DEFAULT_SMTP_PORT));
    mailSender.setUsername(mailProperties.getUsername());
    mailSender.setPassword(mailProperties.getPassword());

    mailSender.getJavaMailProperties().putAll(mailProperties.getProperties());

    return mailSender;
  }
}
