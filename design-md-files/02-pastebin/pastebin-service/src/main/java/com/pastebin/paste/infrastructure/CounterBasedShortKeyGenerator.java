package com.pastebin.paste.infrastructure;

import com.pastebin.paste.domain.ShortKeyGenerator;
import com.pastebin.paste.infrastructure.persistence.PasteRepository;
import com.pastebin.shared.ShortKey;
import org.springframework.stereotype.Component;

@Component
public class CounterBasedShortKeyGenerator implements ShortKeyGenerator {

    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final PasteRepository pasteRepository;

    public CounterBasedShortKeyGenerator(PasteRepository pasteRepository) {
        this.pasteRepository = pasteRepository;
    }

    @Override
    public ShortKey nextKey() {
        long counter = pasteRepository.nextShortKeyCounter();
        long shuffled = counter ^ 0x5DEECE66DL;
        return new ShortKey(encodeBase62(shuffled));
    }

    static String encodeBase62(long value) {
        if (value == 0) {
            return "0";
        }
        StringBuilder encoded = new StringBuilder();
        long current = value;
        while (current > 0) {
            int remainder = (int) (current % 62);
            encoded.append(BASE62.charAt(remainder));
            current /= 62;
        }
        return encoded.reverse().toString();
    }
}
