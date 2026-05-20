package com.pastebin.paste.domain;

import com.pastebin.shared.AccessLevel;
import com.pastebin.shared.ExpiryPolicy;
import com.pastebin.shared.PasteId;
import com.pastebin.shared.ShortKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasteTest {

    @Test
    void rejectsContentOverMaxSize() {
        assertThrows(DomainException.class, () -> Paste.create(
                PasteId.generate(),
                new ShortKey("abc123"),
                null,
                "plaintext",
                com.pastebin.shared.ContentType.INLINE,
                "x",
                null,
                100L,
                "hash",
                ExpiryPolicy.ONE_DAY,
                AccessLevel.PUBLIC,
                null,
                null,
                Instant.now(),
                50L
        ));
    }

    @Test
    void rejectsPrivatePasteWithoutOwner() {
        assertThrows(DomainException.class, () -> Paste.create(
                PasteId.generate(),
                new ShortKey("abc123"),
                null,
                "plaintext",
                com.pastebin.shared.ContentType.INLINE,
                "hello",
                null,
                5L,
                "hash",
                ExpiryPolicy.ONE_DAY,
                AccessLevel.PRIVATE,
                null,
                null,
                Instant.now(),
                1024L
        ));
    }

    @Test
    void allowsPublicPasteReadWhenNotExpired() {
        Paste paste = Paste.create(
                PasteId.generate(),
                new ShortKey("abc123"),
                null,
                "plaintext",
                com.pastebin.shared.ContentType.INLINE,
                "hello",
                null,
                5L,
                "hash",
                ExpiryPolicy.ONE_DAY,
                AccessLevel.PUBLIC,
                null,
                null,
                Instant.now(),
                1024L
        );
        assertDoesNotThrow(() -> paste.assertReadable(java.util.Optional.empty(), true));
    }
}
