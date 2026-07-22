package press.mizhifei.dentist.gateway.security;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** Maps unavailable fail-closed security state to a sanitized 503 response. */
@Component
public final class AccessTokenAuthenticationEntryPoint
        implements ServerAuthenticationEntryPoint, ServerAuthenticationFailureHandler {

    private static final byte[] SERVICE_UNAVAILABLE_BODY = ("{"
            + "\"code\":\"SECURITY_STATE_UNAVAILABLE\","
            + "\"message\":\"Authentication is temporarily unavailable\""
            + "}").getBytes(StandardCharsets.UTF_8);

    private final BearerTokenServerAuthenticationEntryPoint bearerEntryPoint =
            new BearerTokenServerAuthenticationEntryPoint();

    @Override
    public Mono<Void> onAuthenticationFailure(
            WebFilterExchange webFilterExchange,
            AuthenticationException exception) {
        return commence(webFilterExchange.getExchange(), exception);
    }

    @Override
    public Mono<Void> commence(
            ServerWebExchange exchange,
            AuthenticationException exception) {
        if (!(exception instanceof AuthenticationServiceException)) {
            return bearerEntryPoint.commence(exchange, exception);
        }

        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl(CacheControl.noStore());
        response.getHeaders().set(HttpHeaders.PRAGMA, "no-cache");
        response.getHeaders().remove(HttpHeaders.WWW_AUTHENTICATE);
        return response.writeWith(Mono.just(
                response.bufferFactory().wrap(SERVICE_UNAVAILABLE_BODY)));
    }
}
