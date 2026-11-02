package press.mizhifei.dentist.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import press.mizhifei.dentist.gateway.service.PublicApiRateLimiter;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Applies {@link PublicApiRateLimiter} to every routed {@code /api/public/**}
 * request, answering 429 with {@code Retry-After} once a client's window is
 * full. Routes outside the public namespace are untouched.
 */
@Component
public class PublicApiRateLimitFilter implements GlobalFilter, Ordered {

    public static final String PUBLIC_API_PATH = "/api/public";
    private static final byte[] TOO_MANY_REQUESTS_BODY =
            "{\"success\":false,\"message\":\"Too many requests\"}".getBytes(StandardCharsets.UTF_8);

    private final PublicApiRateLimiter rateLimiter;

    public PublicApiRateLimitFilter(PublicApiRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!isPublicApi(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        return rateLimiter.tryAcquire(exchange.getRequest())
                .flatMap(decision -> decision.allowed()
                        ? chain.filter(exchange)
                        : tooManyRequests(exchange.getResponse(), decision.retryAfter()));
    }

    static boolean isPublicApi(String path) {
        return path.equals(PUBLIC_API_PATH) || path.startsWith(PUBLIC_API_PATH + "/");
    }

    private Mono<Void> tooManyRequests(ServerHttpResponse response, Duration retryAfter) {
        long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        HttpHeaders headers = response.getHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl("no-store");
        DataBuffer body = response.bufferFactory().wrap(TOO_MANY_REQUESTS_BODY);
        return response.writeWith(Mono.just(body));
    }

    /** After {@link AnonymousSessionFilter} strips caller-supplied identity headers. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
