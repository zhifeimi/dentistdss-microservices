package press.mizhifei.dentist.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public record AuthenticatedUser(
        String userId,
        String email,
        Set<String> roles,
        Long clinicId) {

    public AuthenticatedUser {
        roles = Set.copyOf(roles);
    }

    public static AuthenticatedUser from(Jwt jwt) {
        return new AuthenticatedUser(
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                roles(jwt.getClaim("roles")),
                clinicId(jwt.getClaim("clinicId")));
    }

    public long requiredNumericUserId() {
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Authenticated user ID is not numeric", ex);
        }
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    private static Set<String> roles(Object claim) {
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        if (claim instanceof Collection<?> values) {
            values.stream()
                    .map(String::valueOf)
                    .filter(StringUtils::hasText)
                    .forEach(roles::add);
        } else if (claim != null && StringUtils.hasText(claim.toString())) {
            roles.add(claim.toString());
        }
        return roles;
    }

    private static Long clinicId(Object claim) {
        if (claim == null) {
            return null;
        }
        if (claim instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(claim.toString());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Authenticated clinic ID is not numeric", ex);
        }
    }
}
