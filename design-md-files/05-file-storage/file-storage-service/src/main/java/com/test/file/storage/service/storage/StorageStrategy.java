package com.test.file.storage.service.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * Strategy abstraction over a physical object store.
 *
 * <p>Each backend (local filesystem, MinIO/S3, ...) implements this interface. The active
 * implementation is selected at runtime by {@link StorageStrategyResolver} based on the
 * {@code app.storage.backend} property. Application services depend only on this interface,
 * never on a concrete backend — that is the whole point of the Strategy pattern here: storage
 * technology is a swappable detail.
 */
public interface StorageStrategy {

    /** Stable identifier used to select this strategy via configuration (e.g. {@code "local"}, {@code "minio"}). */
    String name();

    /**
     * Whether this backend can hand the client a short-lived URL to transfer bytes directly
     * (offloading the app server). MinIO/S3 can; the local filesystem cannot, so callers fall
     * back to streaming through the app.
     */
    boolean supportsPresignedUrls();

    // ---- Single-shot object operations -------------------------------------------------

    void store(String key, InputStream data, long sizeBytes, String contentType);

    InputStream retrieve(String key);

    boolean exists(String key);

    void delete(String key);

    // ---- Presigned URLs (optional capability) ------------------------------------------

    /** @throws UnsupportedOperationException if {@link #supportsPresignedUrls()} is false. */
    String presignedGetUrl(String key, Duration ttl);

    /** @throws UnsupportedOperationException if {@link #supportsPresignedUrls()} is false. */
    String presignedPutUrl(String key, Duration ttl);

    // ---- Multipart / large file upload -------------------------------------------------

    /**
     * Begin a multipart upload for {@code key}. Returns a provider-specific upload id that must
     * be passed to subsequent {@link #uploadPart}/{@link #completeMultipart}/{@link #abortMultipart} calls.
     */
    String initiateMultipart(String key, String contentType);

    PartETag uploadPart(String key, String uploadId, int partNumber, InputStream data, long sizeBytes);

    /** Assemble the previously uploaded parts (in {@code parts} order) into the final object at {@code key}. */
    void completeMultipart(String key, String uploadId, List<PartETag> parts);

    /**
     * Discard an in-progress multipart upload and remove any already-uploaded parts. The known
     * {@code parts} are passed so backends without a native abort handle (e.g. the compose-based
     * MinIO strategy) can delete the temp part objects they created.
     */
    void abortMultipart(String key, String uploadId, List<PartETag> parts);
}
