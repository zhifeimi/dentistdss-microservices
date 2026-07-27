package press.mizhifei.dentist.security.servicetoken;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code app.security.service-auth} for services using service
 * credentials — the issuer key of the calling side and/or the trusted-keys
 * map of the verifying side. Entries with blank key material are ignored so
 * the empty defaults in dev profiles stay dormant instead of breaking
 * startup; verification then fails closed at request time.
 *
 * <p>The {@code trusted-keys} map is keyed by static slugs in YAML
 * ({@code auth}, {@code appointment}, …) because Spring does not resolve
 * placeholders in map keys; each entry carries its own {@code key-id} leaf,
 * which may come from an environment placeholder.</p>
 */
@ConfigurationProperties(prefix = "app.security.service-auth")
public class ServiceAuthProperties {

    /** PKCS#8 PEM private key of this service (callers only). */
    private String privateKey = "";

    /** Key id this service signs with (callers only). */
    private String keyId = "";

    /** This service's registered name, used as the credential subject. */
    private String serviceName = "";

    /** Expected credential audience for this target (verifiers only). */
    private String audience = "";

    /** Trusted caller keys, keyed by a static slug. */
    private Map<String, TrustedKeyEntry> trustedKeys = new LinkedHashMap<>();

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public Map<String, TrustedKeyEntry> getTrustedKeys() {
        return trustedKeys;
    }

    public void setTrustedKeys(Map<String, TrustedKeyEntry> trustedKeys) {
        this.trustedKeys = trustedKeys;
    }

    public ServiceTokenIssuer issuer() {
        return new ServiceTokenIssuer(privateKey, keyId, serviceName);
    }

    /** Returns only fully configured trusted keys; dormant entries are skipped. */
    public List<TrustedServiceKey> trustedServiceKeys() {
        List<TrustedServiceKey> keys = new ArrayList<>();
        trustedKeys.forEach((slug, entry) -> {
            if (StringUtils.hasText(entry.getKeyId())
                    && StringUtils.hasText(entry.getPublicKey())
                    && StringUtils.hasText(entry.getSubject())
                    && !entry.getScopes().isEmpty()) {
                keys.add(new TrustedServiceKey(
                        entry.getKeyId(),
                        entry.getPublicKey(),
                        entry.getSubject(),
                        new HashSet<>(entry.getScopes())));
            }
        });
        return keys;
    }

    public static class TrustedKeyEntry {

        /** The caller's key id its credentials are signed with. */
        private String keyId = "";

        /** X.509 SubjectPublicKeyInfo PEM of the caller's public key. */
        private String publicKey = "";

        /** Expected credential subject (the caller's registered service name). */
        private String subject = "";

        /** Scopes this caller key may mint on this target. */
        private List<String> scopes = new ArrayList<>();

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getSubject() {
            return subject;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }

        public List<String> getScopes() {
            return scopes;
        }

        public void setScopes(List<String> scopes) {
            this.scopes = scopes;
        }
    }
}
