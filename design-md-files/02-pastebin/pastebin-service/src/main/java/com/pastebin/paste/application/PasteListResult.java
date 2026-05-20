package com.pastebin.paste.application;

import java.util.List;

public record PasteListResult(List<PasteSummary> items, String cursor, boolean hasMore) {
}
