package com.blueai.quota;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** The authenticated caller pays for shared-bot previews, never the bot's creator. */
public final class AiUsageActor {
    private static final ThreadLocal<String> BACKGROUND_OWNER = new ThreadLocal<>();
    private AiUsageActor() { }
    public static String operationKind() { return BACKGROUND_OWNER.get() == null ? "COMPLETION" : "SUMMARY"; }
    public static String current() {
        if (BACKGROUND_OWNER.get() != null) return BACKGROUND_OWNER.get();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Jwt jwt ? jwt.getSubject() : null;
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
    public static Scope forOwner(String ownerId) {
        String previous = BACKGROUND_OWNER.get();
        BACKGROUND_OWNER.set(ownerId);
        return () -> { if (previous == null) BACKGROUND_OWNER.remove(); else BACKGROUND_OWNER.set(previous); };
    }
}
