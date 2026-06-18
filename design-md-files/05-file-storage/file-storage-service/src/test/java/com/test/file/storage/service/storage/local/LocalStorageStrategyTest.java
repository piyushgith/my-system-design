package com.test.file.storage.service.storage.local;

import com.test.file.storage.service.storage.PartETag;
import com.test.file.storage.service.storage.StorageException;
import com.test.file.storage.service.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalStorageStrategyTest {

    private LocalStorageStrategy storage;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setBasePath(tempDir.toString());
        storage = new LocalStorageStrategy(props);
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(InputStream in) throws Exception {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void storesAndRetrievesObject() throws Exception {
        storage.store("blobs/abc", stream("hello"), 5, "text/plain");

        assertThat(storage.exists("blobs/abc")).isTrue();
        assertThat(read(storage.retrieve("blobs/abc"))).isEqualTo("hello");
    }

    @Test
    void deleteRemovesObject() {
        storage.store("blobs/abc", stream("hello"), 5, "text/plain");

        storage.delete("blobs/abc");

        assertThat(storage.exists("blobs/abc")).isFalse();
    }

    @Test
    void retrieveMissingObjectThrows() {
        assertThatThrownBy(() -> storage.retrieve("nope"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void doesNotSupportPresignedUrls() {
        assertThat(storage.supportsPresignedUrls()).isFalse();
        assertThatThrownBy(() -> storage.presignedGetUrl("k", Duration.ofMinutes(1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsPathTraversalKeys() {
        assertThatThrownBy(() -> storage.store("../escape", stream("x"), 1, null))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    void multipartConcatenatesPartsInOrder() throws Exception {
        String key = "files/s1/big.bin";
        String uploadId = storage.initiateMultipart(key, "application/octet-stream");

        // Upload out of order to prove completion sorts by part number.
        PartETag p2 = storage.uploadPart(key, uploadId, 2, stream("world"), 5);
        PartETag p1 = storage.uploadPart(key, uploadId, 1, stream("hello"), 5);

        storage.completeMultipart(key, uploadId, List.of(p2, p1));

        assertThat(read(storage.retrieve(key))).isEqualTo("helloworld");
        // Temp parts dir cleaned up after completion.
        assertThat(storage.exists(key + ".parts")).isFalse();
    }

    @Test
    void uploadPartIsOverwritableForSamePartNumber() throws Exception {
        String key = "files/s1/big.bin";
        String uploadId = storage.initiateMultipart(key, null);

        storage.uploadPart(key, uploadId, 1, stream("AAAAA"), 5);
        PartETag rewritten = storage.uploadPart(key, uploadId, 1, stream("BBB"), 3);

        storage.completeMultipart(key, uploadId, List.of(rewritten));

        assertThat(read(storage.retrieve(key))).isEqualTo("BBB");
    }

    @Test
    void abortCleansUpParts() {
        String key = "files/s1/big.bin";
        String uploadId = storage.initiateMultipart(key, null);
        storage.uploadPart(key, uploadId, 1, stream("data"), 4);

        storage.abortMultipart(key, uploadId, List.of());

        assertThat(storage.exists(key + ".parts")).isFalse();
        assertThat(storage.exists(key)).isFalse();
    }
}
