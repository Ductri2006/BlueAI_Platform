package com.blueai.auth.service;

/** Small seam for tests and for the Spring Mail implementation. */
public interface MailSenderGateway {
    void send(String from, String to, String subject, String text);
}
