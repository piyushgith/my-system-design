package com.test.ride.sharing.service.web;

import com.test.ride.sharing.service.config.DataInitializer;
import com.test.ride.sharing.service.shared.UserRole;
import com.test.ride.sharing.service.web.error.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

import java.util.UUID;

public final class DevAuthSupport {

    public static final String HEADER_UID = "X-Uid";
    public static final String HEADER_ROLE = "X-Role";

    public record ResolvedAuth(UUID userId, UserRole role) {
    }

    private DevAuthSupport() {
    }

    public static ResolvedAuth resolve(HttpServletRequest request) {
        return resolve(readUidHeader(request), readRoleHeader(request));
    }

    public static ResolvedAuth resolve(HttpHeaders headers) {
        return resolve(readUidHeader(headers), readRoleHeader(headers));
    }

    public static ResolvedAuth resolve(String uidHeader, String roleHeader) {
        if (uidHeader == null || uidHeader.isBlank()) {
            throw new UnauthorizedException("Missing " + HEADER_UID + " header");
        }

        String uid = uidHeader.trim();
        if ("rider".equalsIgnoreCase(uid)) {
            return new ResolvedAuth(DataInitializer.SEED_RIDER_ID, UserRole.RIDER);
        }
        if ("driver".equalsIgnoreCase(uid)) {
            return new ResolvedAuth(DataInitializer.SEED_DRIVER_ID, UserRole.DRIVER);
        }

        UUID userId;
        try {
            userId = UUID.fromString(uid);
        } catch (IllegalArgumentException ex) {
            throw new UnauthorizedException("Invalid " + HEADER_UID + " header (use rider, driver, or a UUID)");
        }

        if (roleHeader == null || roleHeader.isBlank()) {
            throw new UnauthorizedException("Missing " + HEADER_ROLE + " header when using a UUID");
        }

        UserRole role;
        try {
            role = UserRole.valueOf(roleHeader.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new UnauthorizedException("Invalid " + HEADER_ROLE + " header");
        }

        return new ResolvedAuth(userId, role);
    }

    public static String readUidHeader(HttpServletRequest request) {
        return firstNonBlank(request.getHeader(HEADER_UID), request.getHeader("X-User-Id"));
    }

    public static String readRoleHeader(HttpServletRequest request) {
        return firstNonBlank(request.getHeader(HEADER_ROLE), request.getHeader("X-User-Role"));
    }

    public static String readUidHeader(HttpHeaders headers) {
        return firstNonBlank(headers.getFirst(HEADER_UID), headers.getFirst("X-User-Id"));
    }

    public static String readRoleHeader(HttpHeaders headers) {
        return firstNonBlank(headers.getFirst(HEADER_ROLE), headers.getFirst("X-User-Role"));
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return null;
    }
}
