package com.test.file.storage.service.storage;

/** Result of uploading one part of a multipart upload. */
public record PartETag(int partNumber, String etag, long sizeBytes) {
}
