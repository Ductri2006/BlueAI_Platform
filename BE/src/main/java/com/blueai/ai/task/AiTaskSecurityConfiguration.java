package com.blueai.ai.task;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AiTaskSecurityConfiguration {
    @Bean
    FilterRegistrationBean<AiTaskCredentialFilter> aiCredentialFilterRegistration(AiTaskCredentialFilter filter) {
        FilterRegistrationBean<AiTaskCredentialFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
    @Bean
    FilterRegistrationBean<AiTaskRequestSizeFilter> aiTaskSizeFilterRegistration(AiTaskRequestSizeFilter filter) {
        FilterRegistrationBean<AiTaskRequestSizeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
    @Bean
    @Order(1)
    SecurityFilterChain aiTaskSecurityFilterChain(HttpSecurity http, AiTaskCredentialFilter credentialFilter, AiTaskRequestSizeFilter sizeFilter) throws Exception {
        return http.securityMatcher("/api/v1/ai/tasks/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(sizeFilter, org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .addFilterBefore(credentialFilter, org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class)
                .build();
    }
}
