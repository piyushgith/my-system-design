package com.ecommerce.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Resolves the current authenticated actor for audit logging, without forcing
 * services to take the principal as a method parameter.
 */
public final class AuditContext {

    private AuditContext() {
    }

    /** Current actor id as a string, or "anonymous" when unauthenticated. */
    public static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user.userId().toString();
        }
        return "anonymous";
    }
}
