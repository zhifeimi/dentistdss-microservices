package press.mizhifei.dentist.audit.service;

import org.springframework.stereotype.Component;
import press.mizhifei.dentist.audit.model.AuditEntry;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical content hashing for audit entries (AUDIT-01). The hash covers
 * exactly the stored document fields, in a canonical JSON form, so it is
 * deterministic across processes and recomputable at verification time:
 *
 * <ul>
 *   <li>top-level keys are emitted in fixed alphabetical order
 *       ({@code action, actor, assertedClinicId, assertedUserId, context,
 *       target, timestamp});</li>
 *   <li>{@code timestamp} is the stored {@link java.time.LocalDateTime}'s
 *       ISO-8601 string — verification hashes what is stored, never a
 *       recomputed {@code now()};</li>
 *   <li>{@code context} maps are deep-sorted by key (arrays keep element
 *       order), so key insertion order never affects the hash;</li>
 *   <li>missing values serialize as JSON {@code null}.</li>
 * </ul>
 *
 * <p>Uses its own plain {@link ObjectMapper} — never the web mapper — so
 * application-wide Jackson configuration cannot shift the canonical form.
 */
@Component
public class AuditContentHasher {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

    /**
     * Renders the canonical JSON form of an entry. Exposed for tests and
     * diagnostics; {@link #hash(AuditEntry)} is the production entry point.
     */
    public String canonicalize(AuditEntry entry) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("action", entry.getAction());
        canonical.put("actor", entry.getActor());
        canonical.put("assertedClinicId", entry.getAssertedClinicId());
        canonical.put("assertedUserId", entry.getAssertedUserId());
        canonical.put("context", sortDeep(entry.getContext()));
        canonical.put("target", entry.getTarget());
        canonical.put("timestamp", entry.getTimestamp() == null ? null : entry.getTimestamp().toString());
        return CANONICAL_MAPPER.writeValueAsString(canonical);
    }

    /** Computes the SHA-256 content hash of the entry's canonical form. */
    public String hash(AuditEntry entry) {
        return AuditHashes.sha256Hex(canonicalize(entry));
    }

    private static Object sortDeep(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), sortDeep(nested)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(AuditContentHasher::sortDeep).toList();
        }
        return value;
    }
}
