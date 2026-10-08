package com.blueai.ai.task.security;

import com.blueai.ai.task.model.AiClientPrincipal;
import com.blueai.ai.task.repository.IntegrationClientRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HexFormat;
import java.util.List;

@Component
public class AiTaskCredentialFilter extends OncePerRequestFilter {
    private final IntegrationClientRepository clients;

    public AiTaskCredentialFilter(IntegrationClientRepository clients) {
        this.clients = clients;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/ai/tasks");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null
                || !authorization.startsWith("Bearer ")
                || authorization.length() > 512) {
            unauthorized(response);
            return;
        }
        String token = authorization.substring(7);
        String hash = HexFormat.of().formatHex(CredentialHasher.sha256(token));
        List<AiClientPrincipal> clients = this.clients.findByCredentialHash(hash);
        if (clients.isEmpty()) {
            unauthorized(response);
            return;
        }
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                clients.getFirst(),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_AI_CLIENT"))));
        chain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter()
                .write(
                        "{\"error\":{\"code\":\"INVALID_INTEGRATION_CREDENTIAL\",\"message\":\"A"
                            + " valid integration credential is required.\"}}");
    }
}
