package com.blueai.auth.service;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;

public class SpringMailSenderGateway implements MailSenderGateway {

    private final JavaMailSender sender;

    public SpringMailSenderGateway(JavaMailSender sender) {
        this.sender = sender;
    }

    @Override
    public void send(String from, String to, String subject, String text) {
        MimeMessage message = sender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, false);
            sender.send(message);
        } catch (Exception exception) {
            throw new IllegalStateException("SMTP password-reset delivery failed.", exception);
        }
    }
}
