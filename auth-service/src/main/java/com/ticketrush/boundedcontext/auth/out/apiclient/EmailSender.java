package com.ticketrush.boundedcontext.auth.out.apiclient;

public interface EmailSender {

  void send(String to, String subject, String content);
}
