package press.mizhifei.dentist.security.servicetoken;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Maps a service credential's {@code scope} claim to a granted authority.
 * Deliberately no {@code ROLE_} prefix: these are machine-to-machine scopes,
 * matched by {@code hasAuthority(...)} in endpoint rules, never by
 * {@code hasRole(...)}.
 */
public final class ServiceTokenGrantedAuthorities {

    private ServiceTokenGrantedAuthorities() {
    }

    /** {@code notification:send} → {@code SERVICE_NOTIFICATION_SEND}. */
    public static String authorityForScope(String scope) {
        return "SERVICE_" + scope.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    public static List<GrantedAuthority> authorities(Jwt jwt) {
        String scope = jwt.getClaimAsString("scope");
        if (!StringUtils.hasText(scope)) {
            return List.of();
        }
        return List.of(new SimpleGrantedAuthority(authorityForScope(scope)));
    }
}
