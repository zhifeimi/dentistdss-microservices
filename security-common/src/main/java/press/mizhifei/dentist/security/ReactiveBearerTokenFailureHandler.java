package press.mizhifei.dentist.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reactive bearer failure handling with sanitized infrastructure failures.
 */
public final class ReactiveBearerTokenFailureHandler
        implements ServerAuthenticationEntryPoint, ServerAuthenticationFailureHandler {

    private final BearerTokenServerAuthenticationEntryPoint bearerEntryPoint =
            new BearerTokenServerAuthenticationEntryPoint();

    @Override
    public Mono<Void> commence(
            ServerWebExchange exchange,
            AuthenticationException authenticationException) {
        if (authenticationException instanceof AuthenticationServiceException) {
            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
            exchange.getResponse().getHeaders().setContentLength(0);
            return exchange.getResponse().setComplete();
        }
        return bearerEntryPoint.commence(exchange, authenticationException);
    }

    @Override
    public Mono<Void> onAuthenticationFailure(
            WebFilterExchange webFilterExchange,
            AuthenticationException exception) {
        return commence(webFilterExchange.getExchange(), exception);
    }
}
