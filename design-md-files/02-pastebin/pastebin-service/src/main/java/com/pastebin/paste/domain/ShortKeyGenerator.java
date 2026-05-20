package com.pastebin.paste.domain;

import com.pastebin.shared.ShortKey;

public interface ShortKeyGenerator {
    ShortKey nextKey();
}
