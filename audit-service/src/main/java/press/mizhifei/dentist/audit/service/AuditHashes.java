package press.mizhifei.dentist.audit.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared SHA-256 helper for the tamper-evident audit store (AUDIT-01).
 * Content hashes, batch roots, and seal hashes all derive from this single
 * primitive so the hasher, sealer, and verifier can never disagree on the
 * algorithm or its encoding (lowercase 64-character hex).
 */
public final class AuditHashes {

    private AuditHashes() {
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", e);
        }
    }
}
