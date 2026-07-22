package press.mizhifei.dentist.genai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import press.mizhifei.dentist.security.AuthenticatedUser;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Service for building user context from verified JWT claims and non-authentication
 * session metadata.
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserContextService {

    private static final String SESSION_ID_HEADER = "X-Session-ID";
    private static final String ANONYMOUS_PROOF_HEADER = "X-Gateway-Anonymous-Proof";
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom SESSION_ID_RANDOM = new SecureRandom();

    private final AnonymousSessionRegistry anonymousSessionRegistry;

    /**
     * Builds public-help context only after a gateway-issued one-time proof is
     * atomically consumed in Redis.
     */
    public Mono<UserContext> extractAnonymousUserContext(ServerHttpRequest request) {
        return anonymousSessionRegistry.requireGatewayIssuedSession(
                        request.getHeaders().getFirst(SESSION_ID_HEADER),
                        request.getHeaders().getFirst(ANONYMOUS_PROOF_HEADER))
                .map(session -> UserContext.builder()
                        .sessionId(session.sessionId())
                        .anonymousSourceFingerprint(session.sourceFingerprint())
                        .roles(List.of())
                        .authenticated(false)
                        .build());
    }

    /**
     * Builds authenticated user context from verified JWT claims. Caller-supplied
     * identity, email, role, and clinic headers are deliberately ignored.
     *
     * @param request the HTTP request
     * @param jwt the verified access token
     * @return authenticated user context with non-authentication session metadata
     */
    public UserContext extractUserContext(ServerHttpRequest request, Jwt jwt) {
        if (jwt == null) {
            throw new IllegalArgumentException("Verified JWT is required");
        }

        String sessionId = resolveSessionId(request.getHeaders().getFirst(SESSION_ID_HEADER));
        AuthenticatedUser authenticatedUser = AuthenticatedUser.from(jwt);
        long userId = authenticatedUser.requiredNumericUserId();
        UserContext context = UserContext.builder()
                .sessionId(sessionId)
                .userId(Long.toString(userId))
                .email(authenticatedUser.email())
                .roles(new ArrayList<>(authenticatedUser.roles()))
                .clinicId(authenticatedUser.clinicId() == null
                        ? null
                        : authenticatedUser.clinicId().toString())
                .authenticated(true)
                .build();

        log.debug("Built authenticated GenAI context for user {}", context.getUserId());
        return context;
    }

    /**
     * Gets the primary role for the user (highest priority role).
     *
     * @param context user context
     * @return primary role or "ANONYMOUS" if not authenticated
     */
    public String getPrimaryRole(UserContext context) {
        if (!context.isAuthenticated() || context.getRoles().isEmpty()) {
            return "ANONYMOUS";
        }

        List<String> rolePriority = Arrays.asList(
                "SYSTEM_ADMIN",
                "CLINIC_ADMIN",
                "DENTIST",
                "RECEPTIONIST",
                "PATIENT"
        );

        for (String priorityRole : rolePriority) {
            if (context.getRoles().contains(priorityRole)) {
                return priorityRole;
            }
        }

        return context.getRoles().get(0);
    }

    public boolean hasRole(UserContext context, String role) {
        return context.getRoles().contains(role);
    }

    public boolean hasAnyRole(UserContext context, String... roles) {
        return Arrays.stream(roles).anyMatch(role -> hasRole(context, role));
    }

    public String getDisplayName(UserContext context) {
        if (!context.isAuthenticated()) {
            return "Guest";
        }

        if (StringUtils.hasText(context.getEmail())) {
            int separatorIndex = context.getEmail().indexOf('@');
            if (separatorIndex > 0) {
                String emailName = context.getEmail().substring(0, separatorIndex);
                return capitalizeFirstLetter(emailName.replace('.', ' ').replace('_', ' '));
            }
        }

        return "User";
    }

    private String resolveSessionId(String candidate) {
        if (StringUtils.hasText(candidate)) {
            String sessionId = candidate.trim();
            if (SESSION_ID_PATTERN.matcher(sessionId).matches()) {
                return sessionId;
            }
        }

        byte[] randomBytes = new byte[32];
        SESSION_ID_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String capitalizeFirstLetter(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value.substring(0, 1).toUpperCase() + value.substring(1).toLowerCase();
    }

    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class UserContext {
        private String sessionId;
        private String anonymousSourceFingerprint;
        private String userId;
        private String email;
        private List<String> roles;
        private String clinicId;
        private boolean authenticated;
    }
}
