package com.pastebin.paste.domain;

import com.pastebin.shared.ContentType;

public class ContentRouter {

    private final int inlineThresholdBytes;

    public ContentRouter(int inlineThresholdBytes) {
        this.inlineThresholdBytes = inlineThresholdBytes;
    }

    public ContentRoutingDecision route(String content, String s3Key) {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length < inlineThresholdBytes) {
            return new ContentRoutingDecision(ContentType.INLINE, content, null);
        }
        return new ContentRoutingDecision(ContentType.S3, null, s3Key);
    }
}
