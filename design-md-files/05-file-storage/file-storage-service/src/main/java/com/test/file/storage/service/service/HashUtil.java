package com.test.file.storage.service.service;

import com.test.file.storage.service.storage.StorageException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helper used for content-addressed deduplication. */
public final class HashUtil {

    private HashUtil() {
    }

    /** Streaming SHA-256 of a file on disk, returned as lowercase hex. */
    public static String sha256(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new StorageException("Failed to hash content", e);
        }
    }
}
