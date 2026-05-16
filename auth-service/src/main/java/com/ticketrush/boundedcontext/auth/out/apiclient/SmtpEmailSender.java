package com.ticketrush.boundedcontext.auth.out.apiclient;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Profile("local")
public class SmtpEmailSender implements EmailSender {

  private final JavaMailSender javaMailSender;

  @Override
  public void send(String to, String subject, String content) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setTo(to);
    message.setSubject(subject);
    message.setText(content);

    javaMailSender.send(message);
  }
}
