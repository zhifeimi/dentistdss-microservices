package press.mizhifei.dentist.security;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

import java.util.Objects;

/**
 * Reusable servlet resource-server configuration for custom security chains.
 */
public final class ServletJwtResourceServerCustomizer
        implements Customizer<OAuth2ResourceServerConfigurer<HttpSecurity>> {

    private final RedisAccessTokenJwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter authenticationConverter;
    private final ServletBearerTokenFailureHandler failureHandler;

    public ServletJwtResourceServerCustomizer(
            RedisAccessTokenJwtDecoder jwtDecoder,
            JwtAuthenticationConverter authenticationConverter,
            ServletBearerTokenFailureHandler failureHandler) {
        this.jwtDecoder = Objects.requireNonNull(jwtDecoder, "jwtDecoder");
        this.authenticationConverter = Objects.requireNonNull(
                authenticationConverter,
                "authenticationConverter");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    @Override
    public void customize(OAuth2ResourceServerConfigurer<HttpSecurity> resourceServer) {
        resourceServer
                .authenticationEntryPoint(failureHandler)
                .jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(authenticationConverter))
                .addObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                    @Override
                    public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                        filter.setAuthenticationEntryPoint(failureHandler);
                        filter.setAuthenticationFailureHandler(failureHandler);
                        return filter;
                    }
                });
    }
}
