package com.pastebin.paste.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CounterBasedShortKeyGeneratorTest {

    @Test
    void encodesBase62() {
        assertEquals("1", CounterBasedShortKeyGenerator.encodeBase62(1));
        assertEquals("10", CounterBasedShortKeyGenerator.encodeBase62(62));
    }
}
