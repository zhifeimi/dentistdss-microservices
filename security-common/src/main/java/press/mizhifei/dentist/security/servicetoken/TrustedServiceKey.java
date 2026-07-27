package press.mizhifei.dentist.security.servicetoken;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;

/**
 * A caller key a target service trusts: the key id the caller signs with, the
 * caller's RSA public key, the caller's expected subject (registered service
 * name), and the exact scopes this key may mint. Binding scopes per key means
 * a compromised or less-privileged caller key cannot escalate: an
 * {@code appointment-service} key minting {@code notification:email} is
 * rejected even though its signature is valid.
 */
public record TrustedServiceKey(
        String keyId,
        String publicKeyPem,
        String subject,
        Set<String> scopes) {

    public TrustedServiceKey {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(publicKeyPem, "publicKeyPem");
        Objects.requireNonNull(subject, "subject");
        scopes = Set.copyOf(scopes);
    }

    /** Parses a PEM-encoded X.509 SubjectPublicKeyInfo RSA public key. */
    public static RSAPublicKey parseRsaPublicKey(String pem) throws GeneralSecurityException {
        String normalized = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(normalized)));
    }
}
