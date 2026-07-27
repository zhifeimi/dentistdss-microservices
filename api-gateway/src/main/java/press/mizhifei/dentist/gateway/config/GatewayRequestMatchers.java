package press.mizhifei.dentist.gateway.config;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;

/**
 * Shared exact request matchers for gateway authorization and request enrichment.
 */
public final class GatewayRequestMatchers {

    public static final String ANONYMOUS_HELP_PATH = "/api/genai/chatbot/help";

    public static final ServerWebExchangeMatcher ANONYMOUS_HELP = exchange ->
            isAnonymousHelp(exchange.getRequest())
                    ? ServerWebExchangeMatcher.MatchResult.match()
                    : ServerWebExchangeMatcher.MatchResult.notMatch();

    private GatewayRequestMatchers() {
    }

    public static boolean isAnonymousHelp(ServerHttpRequest request) {
        return HttpMethod.POST.equals(request.getMethod())
                && ANONYMOUS_HELP_PATH.equals(request.getURI().getRawPath());
    }
}
