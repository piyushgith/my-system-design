package com.test.kyc.identification.common.encryption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for PII blobs.
 * Layout: [12-byte IV][16-byte auth tag embedded by GCM][ciphertext]
 * Key version is stored alongside the ciphertext so future key rotation
 * can decrypt old records before re-encrypting with the new key.
 */
@Service
public class PiiEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String KEY_VERSION = "v1";

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public PiiEncryptionService(@Value("${kyc.encryption.secret-key}") String rawKey) {
        byte[] keyBytes = rawKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("Encryption key must be at least 32 bytes");
        }
        byte[] key32 = new byte[32];
        System.arraycopy(keyBytes, 0, key32, 0, 32);
        this.secretKey = new SecretKeySpec(key32, "AES");
    }

    public byte[] encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buf = ByteBuffer.allocate(IV_LENGTH_BYTES + ciphertext.length);
            buf.put(iv);
            buf.put(ciphertext);
            return buf.array();
        } catch (Exception e) {
            throw new EncryptionException("Failed to encrypt PII", e);
        }
    }

    public String decrypt(byte[] encrypted) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(encrypted);

            byte[] iv = new byte[IV_LENGTH_BYTES];
            buf.get(iv);

            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new EncryptionException("Failed to decrypt PII", e);
        }
    }

    public String currentKeyVersion() {
        return KEY_VERSION;
    }

    public byte[] encryptS3Key(String s3Key) {
        return encrypt(s3Key);
    }

    public String decryptS3Key(byte[] encrypted) {
        return decrypt(encrypted);
    }
}
