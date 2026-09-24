package com.blueai.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.auth.password-reset")
public class PasswordResetProperties {

    private boolean enabled;

    @NotNull
    private Duration tokenTtl = Duration.ofMinutes(30);

    @NotBlank
    private String frontendUrl = "http://localhost:3000/reset-password";

    private String mailFrom = "";

    @NotNull
    private Duration requestCooldown = Duration.ofMinutes(5);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getTokenTtl() { return tokenTtl; }
    public void setTokenTtl(Duration tokenTtl) { this.tokenTtl = tokenTtl; }
    public String getFrontendUrl() { return frontendUrl; }
    public void setFrontendUrl(String frontendUrl) { this.frontendUrl = frontendUrl; }
    public String getMailFrom() { return mailFrom; }
    public void setMailFrom(String mailFrom) { this.mailFrom = mailFrom; }
    public Duration getRequestCooldown() { return requestCooldown; }
    public void setRequestCooldown(Duration requestCooldown) { this.requestCooldown = requestCooldown; }
}
