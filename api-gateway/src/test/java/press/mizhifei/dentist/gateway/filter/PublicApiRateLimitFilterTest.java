package press.mizhifei.dentist.gateway.filter;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import press.mizhifei.dentist.gateway.service.PublicApiRateLimiter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublicApiRateLimitFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/public",
            "/api/public/",
            "/api/public/clinics",
            "/api/public/clinics/smile-dental",
            "/api/%70ublic",
            "/api/%70ublic/clinics",
            "/%61pi/%70ublic/clinics",
            "/api/public/clinics%2Fsmile-dental"
    })
    void exhaustedQuotaBlocksThePublicNamespace(String path) {
        PublicApiRateLimiter rateLimiter = mock(PublicApiRateLimiter.class);
        when(rateLimiter.tryAcquire(any())).thenReturn(Mono.just(
                new PublicApiRateLimiter.Decision(false, Duration.ofSeconds(2))));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, URI.create(path)).build());

        StepVerifier.create(new PublicApiRateLimitFilter(rateLimiter).filter(exchange,
                        downstream -> Mono.error(new AssertionError("Blocked request reached the backend"))))
                .verifyComplete();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/clinic",
            "/api/publicity",
            "/api/publicity/clinics",
            "/api/%70ublicity/clinics",
            "/api%2Fpublic/clinics",
            "/api/public%2Fclinics",
            "/api/%70ublic%2fclinics",
            "/api/%2570ublic/clinics",
            "/api/public%252Fclinics"
    })
    void pathsOutsideThePublicSegmentsDoNotConsumeQuota(String path) {
        PublicApiRateLimiter rateLimiter = mock(PublicApiRateLimiter.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, URI.create(path)).build());

        StepVerifier.create(new PublicApiRateLimitFilter(rateLimiter).filter(exchange, downstream -> {
                    downstream.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
                    return downstream.getResponse().setComplete();
                }))
                .verifyComplete();

        assertEquals(HttpStatus.NO_CONTENT, exchange.getResponse().getStatusCode());
        verifyNoInteractions(rateLimiter);
    }
}
