package press.mizhifei.dentist.security.servicetoken;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.WebFilterChainServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/**
 * Builds the reactive {@link AuthenticationWebFilter} that accepts service
 * credentials on the exact endpoint matchers a target configures. Mirrors the
 * GenAI anonymous-help filter: the converter stays inactive unless the request
 * matches, carries no user {@code Authorization} header, and presents a
 * {@code Bearer} credential in {@link ServiceTokenConstants#HEADER_NAME} —
 * so a spoofed service header can never shadow a legitimate user request.
 */
public final class ServiceTokenWebFilters {

    private ServiceTokenWebFilters() {
    }

    public static AuthenticationWebFilter reactiveAuthenticationFilter(
            ReactiveJwtDecoder serviceJwtDecoder,
            ServerWebExchangeMatcher requestMatcher,
            ServerAuthenticationFailureHandler failureHandler) {
        JwtReactiveAuthenticationManager jwtManager =
                new JwtReactiveAuthenticationManager(serviceJwtDecoder);
        jwtManager.setJwtAuthenticationConverter(jwt -> Mono.just(
                new JwtAuthenticationToken(
                        jwt,
                        ServiceTokenGrantedAuthorities.authorities(jwt),
                        jwt.getSubject())));
        ReactiveAuthenticationManager authenticationManager = jwtManager;
        AuthenticationWebFilter filter = new AuthenticationWebFilter(authenticationManager);
        filter.setServerAuthenticationConverter(exchange -> requestMatcher.matches(exchange)
                .filter(ServerWebExchangeMatcher.MatchResult::isMatch)
                .filter(match -> !StringUtils.hasText(
                        exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)))
                .flatMap(match -> extractBearerToken(exchange)));
        filter.setAuthenticationFailureHandler(failureHandler);
        filter.setAuthenticationSuccessHandler(
                new WebFilterChainServerAuthenticationSuccessHandler());
        return filter;
    }

    private static Mono<BearerTokenAuthenticationToken> extractBearerToken(
            org.springframework.web.server.ServerWebExchange exchange) {
        String serviceAuthorization = exchange.getRequest().getHeaders()
                .getFirst(ServiceTokenConstants.HEADER_NAME);
        if (!StringUtils.hasText(serviceAuthorization)
                || !serviceAuthorization.startsWith(ServiceTokenConstants.BEARER_PREFIX)
                || !StringUtils.hasText(
                        serviceAuthorization.substring(ServiceTokenConstants.BEARER_PREFIX.length()))) {
            return Mono.empty();
        }
        return Mono.just(new BearerTokenAuthenticationToken(
                serviceAuthorization.substring(ServiceTokenConstants.BEARER_PREFIX.length())));
    }
}
