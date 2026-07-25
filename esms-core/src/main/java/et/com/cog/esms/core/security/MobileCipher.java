package et.com.cog.esms.core.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Protects staff mobile numbers at rest, filling in the two columns V001
 * declared but nothing ever wrote:
 *
 *   app_user.mobile_enc  - [12-byte IV][AES-GCM ciphertext+tag]
 *   app_user.mobile_hash - SHA-256 hex of the normalised E.164 form
 *
 * The hash allows a number to be matched or counted without decrypting it;
 * only the login path, which has to actually dial it, ever decrypts.
 */
@Slf4j
@Component
public class MobileCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS  = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public MobileCipher(@Value("${app.security.mobile.encryption-key}") String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.security.mobile.encryption-key must be base64", e);
        }
        if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
            throw new IllegalStateException(
                    "app.security.mobile.encryption-key must decode to 16, 24 or 32 bytes, got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** Returns null for blank input so an unset mobile stays a NULL column. */
    public byte[] encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt mobile number", e);
        }
    }

    /**
     * Returns null when nothing is stored, or when the value cannot be read
     * back (wrong or rotated key). Callers treat both as "no usable number"
     * rather than failing the request; a decrypt failure is logged loudly
     * because it means the key changed.
     */
    public String decrypt(byte[] stored) {
        if (stored == null || stored.length <= IV_LENGTH) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, IV_LENGTH));
            return new String(cipher.doFinal(stored, IV_LENGTH, stored.length - IV_LENGTH),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Could not decrypt a stored mobile number - does "
                    + "app.security.mobile.encryption-key match the key the data was written with?");
            return null;
        }
    }

    /** SHA-256 hex, exactly the 64 chars mobile_hash is sized for. */
    public String hash(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash mobile number", e);
        }
    }
}
