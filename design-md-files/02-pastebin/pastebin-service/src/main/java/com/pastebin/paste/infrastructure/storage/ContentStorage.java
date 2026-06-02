package com.pastebin.paste.infrastructure.storage;

public interface ContentStorage {
    void upload(String key, String content);
    String download(String key);
    void delete(String key);
    String buildKey(String pasteId);
}
