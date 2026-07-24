package press.mizhifei.dentist.security;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;

import java.util.Objects;

/**
 * Reusable reactive resource-server configuration for custom security chains.
 */
public final class ReactiveJwtResourceServerCustomizer
        implements Customizer<ServerHttpSecurity.OAuth2ResourceServerSpec> {

    private final ReactiveJwtDecoder jwtDecoder;
    private final ReactiveJwtAuthenticationConverterAdapter authenticationConverter;
    private final ReactiveBearerTokenFailureHandler failureHandler;

    public ReactiveJwtResourceServerCustomizer(
            ReactiveJwtDecoder jwtDecoder,
            ReactiveJwtAuthenticationConverterAdapter authenticationConverter,
            ReactiveBearerTokenFailureHandler failureHandler) {
        this.jwtDecoder = Objects.requireNonNull(jwtDecoder, "jwtDecoder");
        this.authenticationConverter = Objects.requireNonNull(
                authenticationConverter,
                "authenticationConverter");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    @Override
    public void customize(ServerHttpSecurity.OAuth2ResourceServerSpec resourceServer) {
        resourceServer
                .authenticationEntryPoint(failureHandler)
                .authenticationFailureHandler(failureHandler)
                .jwt(jwt -> jwt
                        .jwtDecoder(jwtDecoder)
                        .jwtAuthenticationConverter(authenticationConverter));
    }
}
