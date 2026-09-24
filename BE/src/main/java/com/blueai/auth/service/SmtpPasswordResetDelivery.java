package com.blueai.auth.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class SmtpPasswordResetDelivery implements PasswordResetDelivery {

    private final MailSenderGateway mailSender;
    private final String from;
    private final String resetBaseUrl;

    public SmtpPasswordResetDelivery(MailSenderGateway mailSender, String from, String resetBaseUrl) {
        this.mailSender = mailSender;
        this.from = from;
        this.resetBaseUrl = resetBaseUrl;
    }

    @Override
    public void deliver(String recipientEmail, String rawToken) {
        String link = resetBaseUrl + (resetBaseUrl.contains("?") ? "&" : "?")
                + "token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        mailSender.send(
                from,
                recipientEmail,
                "Reset your BlueAI password",
                "Use this one-time link to reset your BlueAI password: " + link
                        + "\n\nThis link expires soon and can only be used once."
        );
    }
}
