package press.mizhifei.dentist.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import press.mizhifei.dentist.gateway.service.TrustedProxyClientAddressResolver;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Global logging filter for the API Gateway
 * Logs all incoming requests and outgoing responses
 * 
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 */
@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(LoggingFilter.class);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TrustedProxyClientAddressResolver clientAddressResolver;

    public LoggingFilter(TrustedProxyClientAddressResolver clientAddressResolver) {
        this.clientAddressResolver = clientAddressResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        String timestamp = LocalDateTime.now().format(formatter);
        String method = request.getMethod().toString();
        String path = request.getPath().toString();
        String remoteAddress = clientAddressResolver.resolve(request);
        String userAgent = request.getHeaders().getFirst("User-Agent");

        // Log incoming request
        logger.info("INCOMING REQUEST - {} | {} {} | IP: {} | User-Agent: {}",
                timestamp, method, path, remoteAddress, userAgent);
        // Continue with the filter chain and log response
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            org.springframework.http.HttpStatusCode statusCodeObj = response.getStatusCode();
            int statusCode = statusCodeObj != null ? statusCodeObj.value() : 0;
            String responseTimestamp = LocalDateTime.now().format(formatter);

            logger.info("OUTGOING RESPONSE - {} | {} {} | Status: {} | IP: {}",
                    responseTimestamp, method, path, statusCode, remoteAddress);
        }));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE; // Execute last to capture final response
    }
}
