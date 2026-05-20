package com.pastebin.paste.domain;

import com.pastebin.shared.ContentType;

public record ContentRoutingDecision(ContentType contentType, String inlineContent, String s3Key) {
}
