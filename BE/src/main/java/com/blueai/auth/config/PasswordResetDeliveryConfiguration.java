package com.blueai.auth.config;

import com.blueai.auth.service.LocalSafePasswordResetDelivery;
import com.blueai.auth.service.PasswordResetDelivery;
import com.blueai.auth.service.SpringMailSenderGateway;
import com.blueai.auth.service.SmtpPasswordResetDelivery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
@EnableConfigurationProperties(PasswordResetProperties.class)
public class PasswordResetDeliveryConfiguration {

    @Bean
    PasswordResetDelivery passwordResetDelivery(
            Environment environment,
            PasswordResetProperties properties,
            ObjectProvider<JavaMailSender> mailSender
    ) {
        boolean production = environment.acceptsProfiles("prod", "production");
        if (!properties.isEnabled() && !production) {
            return new LocalSafePasswordResetDelivery();
        }
        if (!properties.isEnabled()) {
            throw new IllegalStateException("app.auth.password-reset.enabled must be true in production.");
        }
        if (properties.getMailFrom() == null || properties.getMailFrom().isBlank()) {
            throw new IllegalStateException("app.auth.password-reset.mail-from is required when reset is enabled.");
        }
        if (properties.getFrontendUrl() == null || properties.getFrontendUrl().isBlank()) {
            throw new IllegalStateException("app.auth.password-reset.frontend-url is required when reset is enabled.");
        }
        JavaMailSender sender = mailSender.getIfAvailable(() -> {
            throw new IllegalStateException("Spring JavaMailSender is required when password reset is enabled.");
        });
        return new SmtpPasswordResetDelivery(
                new SpringMailSenderGateway(sender),
                properties.getMailFrom().trim(),
                properties.getFrontendUrl().trim()
        );
    }
}
