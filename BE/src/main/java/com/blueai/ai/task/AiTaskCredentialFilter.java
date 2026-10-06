package com.blueai.ai.task;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Component
public class AiTaskCredentialFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    public AiTaskCredentialFilter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/ai/tasks");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 512) {
            unauthorized(response); return;
        }
        String token = authorization.substring(7);
        String hash = HexFormat.of().formatHex(sha256(token));
        List<AiClientPrincipal> clients = jdbc.query("""
                select c.id, c.owner_id from ai_integration_clients c
                join app_users u on u.id=c.owner_id
                where c.credential_hash=? and c.active=true and c.revoked_at is null
                  and u.status='ACTIVE' and u.deleted_at is null and u.role='ADMIN'
                """, (rs, row) -> new AiClientPrincipal(rs.getString(1), rs.getString(2)), hash);
        if (clients.isEmpty()) { unauthorized(response); return; }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                clients.getFirst(), null, List.of(new SimpleGrantedAuthority("ROLE_AI_CLIENT"))));
        chain.doFilter(request, response);
    }

    static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":{\"code\":\"INVALID_INTEGRATION_CREDENTIAL\",\"message\":\"A valid integration credential is required.\"}}");
    }
}

record AiClientPrincipal(String clientId, String ownerId) { }
