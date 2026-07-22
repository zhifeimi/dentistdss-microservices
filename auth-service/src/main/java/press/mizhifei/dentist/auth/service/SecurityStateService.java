package press.mizhifei.dentist.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SecurityStateService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String FAMILY_ACTIVE = "active";
    private static final String FAMILY_REVOKED = "revoked";

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> PUBLISH_ACCOUNT_SCRIPT = new DefaultRedisScript<>("""
            local requested = ARGV[1] .. ':' .. ARGV[2]
            local current = redis.call('GET', KEYS[1])
            if not current then
              redis.call('SET', KEYS[1], requested)
              return 1
            end
            if current == requested then
              return 1
            end
            local separator = string.find(current, ':', 1, true)
            if not separator or string.find(current, ':', separator + 1, true) then
              return 0
            end
            local current_version = tonumber(string.sub(current, 1, separator - 1))
            local current_active = string.sub(current, separator + 1)
            local next_version = tonumber(ARGV[1])
            if not current_version or current_version < 1
                or (current_active ~= '0' and current_active ~= '1')
                or not next_version or next_version <= current_version then
              return 0
            end
            redis.call('SET', KEYS[1], requested)
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RESTORE_ACCOUNT_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
              return 0
            end
            if ARGV[2] == '' then
              redis.call('DEL', KEYS[1])
            else
              redis.call('SET', KEYS[1], ARGV[2])
            end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> ACTIVATE_FAMILY_SCRIPT = new DefaultRedisScript<>("""
            local result = redis.call('SET', KEYS[1], 'active', 'PX', ARGV[1], 'NX')
            if result then
              return 1
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> EXTEND_ACTIVE_FAMILY_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= 'active' then
              return 0
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public String issueGoogleNonce() {
        String nonce = randomToken(32);
        redisTemplate.opsForValue().set("security:google-nonce:" + nonce, nonce, Duration.ofMinutes(5));
        return nonce;
    }

    public boolean consumeGoogleNonce(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return false;
        }
        Long consumed = redisTemplate.execute(
                CONSUME_SCRIPT,
                List.of("security:google-nonce:" + nonce),
                nonce);
        return consumed != null && consumed == 1L;
    }

    public boolean isAllowed(String category, String identity, int limit, Duration window) {
        String normalizedIdentity = identity == null ? "unknown" : identity.trim().toLowerCase();
        Long count = redisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                List.of("security:rate:" + category + ":" + normalizedIdentity),
                Long.toString(window.toMillis()));
        return count != null && count <= limit;
    }

    public void publishAccountState(long userId, long securityVersion, boolean active) {
        requirePositive(userId, "user ID");
        requirePositive(securityVersion, "security version");
        Long published = redisTemplate.execute(
                PUBLISH_ACCOUNT_SCRIPT,
                List.of(accountKey(userId)),
                Long.toString(securityVersion),
                active ? "1" : "0");
        if (published == null || published != 1L) {
            throw new SecurityStateUnavailableException();
        }
    }

    public boolean restoreAccountStateIfCurrent(
            long userId,
            AccountSecurityState expected,
            Optional<AccountSecurityState> previous) {
        requirePositive(userId, "user ID");
        if (expected == null || previous == null) {
            throw new IllegalArgumentException("Expected and previous account states are required");
        }
        Long restored = redisTemplate.execute(
                RESTORE_ACCOUNT_SCRIPT,
                List.of(accountKey(userId)),
                accountValue(expected),
                previous.map(SecurityStateService::accountValue).orElse(""));
        return restored != null && restored == 1L;
    }

    public Optional<AccountSecurityState> readAccountState(long userId) {
        requirePositive(userId, "user ID");
        String value = redisTemplate.opsForValue().get(accountKey(userId));
        if (value == null) {
            return Optional.empty();
        }
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':')) {
            throw new SecurityStateUnavailableException();
        }
        String version = value.substring(0, separator);
        String active = value.substring(separator + 1);
        if (!version.matches("[1-9][0-9]*") || !(active.equals("0") || active.equals("1"))) {
            throw new SecurityStateUnavailableException();
        }
        try {
            return Optional.of(new AccountSecurityState(
                    Long.parseLong(version),
                    active.equals("1")));
        } catch (NumberFormatException ex) {
            throw new SecurityStateUnavailableException(ex);
        }
    }

    public boolean activateFamily(long userId, String familyId, Duration lifetime) {
        Long activated = redisTemplate.execute(
                ACTIVATE_FAMILY_SCRIPT,
                List.of(familyKey(userId, familyId)),
                durationMillis(lifetime));
        return activated != null && activated == 1L;
    }

    public boolean extendActiveFamily(long userId, String familyId, Duration lifetime) {
        Long extended = redisTemplate.execute(
                EXTEND_ACTIVE_FAMILY_SCRIPT,
                List.of(familyKey(userId, familyId)),
                durationMillis(lifetime));
        return extended != null && extended == 1L;
    }

    public void revokeFamily(long userId, String familyId, Duration lifetime) {
        redisTemplate.opsForValue().set(
                familyKey(userId, familyId),
                FAMILY_REVOKED,
                lifetime);
    }

    public Optional<FamilySecurityState> readFamilyState(long userId, String familyId) {
        String value = redisTemplate.opsForValue().get(familyKey(userId, familyId));
        if (FAMILY_ACTIVE.equals(value)) {
            return Optional.of(FamilySecurityState.ACTIVE);
        }
        if (FAMILY_REVOKED.equals(value)) {
            return Optional.of(FamilySecurityState.REVOKED);
        }
        if (value != null) {
            throw new SecurityStateUnavailableException();
        }
        return Optional.empty();
    }

    public String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        SECURE_RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static String accountKey(long userId) {
        requirePositive(userId, "user ID");
        return "security:access:v1:{" + userId + "}:account";
    }

    static String familyKey(long userId, String familyId) {
        requirePositive(userId, "user ID");
        if (familyId == null || familyId.isBlank() || familyId.length() > 128) {
            throw new IllegalArgumentException("Valid session family ID is required");
        }
        return "security:access:v1:{" + userId + "}:family:" + familyId;
    }

    private static String accountValue(AccountSecurityState state) {
        requirePositive(state.securityVersion(), "security version");
        return state.securityVersion() + ":" + (state.active() ? "1" : "0");
    }

    private static String durationMillis(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Positive security-state lifetime is required");
        }
        return Long.toString(duration.toMillis());
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("Positive " + name + " is required");
        }
    }

    public record AccountSecurityState(long securityVersion, boolean active) {
    }

    public enum FamilySecurityState {
        ACTIVE,
        REVOKED
    }

    public static class SecurityStateUnavailableException extends IllegalStateException {
        public SecurityStateUnavailableException() {
            super("Authentication state unavailable");
        }

        public SecurityStateUnavailableException(Throwable cause) {
            super("Authentication state unavailable", cause);
        }
    }
}
