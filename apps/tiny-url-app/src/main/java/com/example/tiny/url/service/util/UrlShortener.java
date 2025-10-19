package com.example.tiny.url.service.util;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.security.MessageDigest;

// NOTE: This example uses a simplified custom Base62 logic.
// In a real application, you'd use a robust Base62 library.

@Component
public class UrlShortener {

    // Define the character set for Base62: 0-9, A-Z, a-z (62 characters)
    private static final String BASE62_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /**
     * Generates a unique short code by hashing the long URL and Base62 encoding a portion of the hash.
     * @param longUrl The original long URL.
     * @return A unique short code string.
     */
    public String generateUniqueShortCode(String longUrl) {
        try {
            // 1. Hash Generation (using SHA-256)
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(longUrl.getBytes("UTF-8"));

            // 2. Truncation and Conversion
            // We'll take the first 8 bytes (64 bits) of the hash.
            // 64 bits offers sufficient space (62^10 > 3.6e17 possible codes for a 10-char length).
            long uniqueId = ByteBuffer.wrap(hash, 0, 8).getLong();

            // Ensure the number is positive for cleaner Base62 conversion
            uniqueId = Math.abs(uniqueId);

            // 3. Base62 Encoding
            return toBase62(uniqueId);

        } catch (Exception e) {
            // In a real app, handle the exception (e.g., log it or throw a custom runtime exception)
            throw new RuntimeException("Error generating short code.", e);
        }
    }

    /**
     * Custom Base62 encoding logic for a long value.
     */
    private String toBase62(long value) {
        // A short URL of 8-10 characters is common, which is the output of this logic.
        StringBuilder sb = new StringBuilder();
        if (value == 0) {
            return String.valueOf(BASE62_CHARS.charAt(0));
        }

        while (value > 0) {
            sb.insert(0, BASE62_CHARS.charAt((int) (value % 62)));
            value /= 62;
        }
        return sb.toString();
    }
}
