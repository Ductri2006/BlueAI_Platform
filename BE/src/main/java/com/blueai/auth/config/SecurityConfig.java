package com.blueai.auth.config;

import com.blueai.auth.exception.RestAccessDeniedHandler;
import com.blueai.auth.exception.RestAuthenticationEntryPoint;
import com.blueai.auth.model.User;
import com.blueai.auth.repository.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/internal/documents/**",
                                "/api/v1/widget/**",
                                "/api/v1/runtime/**",
                                "/actuator/prometheus",
                                "/actuator/health",
                                "/actuator/health/**"
                        ).permitAll()
                        // Health and the internal-network Prometheus scrape are public at
                        // the app security boundary. The deployment network must still
                        // restrict exposure of the app port; all other actuator data is
                        // operationally sensitive.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**", "/api/v1/documents/**", "/api/v1/system/**", "/api/v1/studio/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(properties.getSecret());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("APP_JWT_SECRET must be valid Base64.", exception);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException("APP_JWT_SECRET must decode to at least 32 bytes for HS256.");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey secretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey secretKey, JwtProperties properties, UserRepository userRepository) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(properties.getIssuer());
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                audience -> audience != null && audience.contains(properties.getAudience())
        );
        OAuth2TokenValidator<Jwt> tokenTypeValidator = new JwtClaimValidator<String>(
                "type",
                "access"::equals
        );
        OAuth2TokenValidator<Jwt> accountValidator = jwt -> {
            User user = userRepository.findById(jwt.getSubject()).orElse(null);
            Number tokenVersion = jwt.getClaim("authVersion");
            if (user == null || user.getStatus() != com.blueai.auth.model.UserStatus.ACTIVE
                    || user.isDeleted() || tokenVersion == null
                    || tokenVersion.longValue() != user.getAuthVersion()) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "The account is disabled or the access token has been revoked.",
                        null
                ));
            }
            return OAuth2TokenValidatorResult.success();
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerValidator,
                audienceValidator,
                tokenTypeValidator,
                accountValidator
        ));
        return decoder;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration applicationConfiguration = new CorsConfiguration();
        applicationConfiguration.setAllowedOrigins(properties.getAllowedOrigins());
        applicationConfiguration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        applicationConfiguration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-ID"));
        applicationConfiguration.setExposedHeaders(List.of("X-Request-ID"));
        applicationConfiguration.setAllowCredentials(true);
        applicationConfiguration.setMaxAge(3600L);

        // The embedded widget is intentionally public and never sends browser
        // credentials. It must therefore use its own origin policy instead of
        // inheriting the authenticated application policy above. Origin
        // patterns allow the documented "*" default and explicit production
        // allowlists via APP_WIDGET_ALLOWED_ORIGINS.
        CorsConfiguration widgetConfiguration = new CorsConfiguration();
        widgetConfiguration.setAllowedOriginPatterns(properties.getWidgetAllowedOrigins());
        widgetConfiguration.setAllowedMethods(List.of("POST", "OPTIONS"));
        widgetConfiguration.setAllowedHeaders(List.of("Content-Type", "X-Widget-Token"));
        // widget.js reads this header to tell a visitor when a 429 can be retried.
        widgetConfiguration.setExposedHeaders(List.of("Retry-After"));
        widgetConfiguration.setAllowCredentials(false);
        widgetConfiguration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/widget/**", widgetConfiguration);
        source.registerCorsConfiguration("/api/v1/runtime/**", widgetConfiguration);
        source.registerCorsConfiguration("/**", applicationConfiguration);
        return source;
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        authenticationConverter.setPrincipalClaimName("email");
        return authenticationConverter;
    }
}
