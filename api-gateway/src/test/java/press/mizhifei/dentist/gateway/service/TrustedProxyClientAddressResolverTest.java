package press.mizhifei.dentist.gateway.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrustedProxyClientAddressResolverTest {

    @Test
    void ignoresForwardedHeadersWhenNoTrustedProxyIsConfigured() {
        TrustedProxyClientAddressResolver resolver =
                new TrustedProxyClientAddressResolver(0);
        var request = MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress("203.0.113.10", 443))
                .header("X-Forwarded-For", "198.51.100.1")
                .build();

        assertEquals("203.0.113.10", resolver.resolve(request));
    }

    @Test
    void selectsClientFromRightOfTrustedProxyChain() {
        TrustedProxyClientAddressResolver resolver =
                new TrustedProxyClientAddressResolver(2);
        var request = MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 443))
                .header("X-Forwarded-For", "192.0.2.99, 198.51.100.24, 10.0.0.4")
                .build();

        assertEquals("198.51.100.24", resolver.resolve(request));
    }

    @Test
    void malformedOrShortForwardedChainFallsBackToDirectPeer() {
        TrustedProxyClientAddressResolver resolver =
                new TrustedProxyClientAddressResolver(2);
        var malformed = MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 443))
                .header("X-Forwarded-For", "attacker.example, 10.0.0.4")
                .build();
        var shortChain = MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 443))
                .header("X-Forwarded-For", "198.51.100.24")
                .build();

        assertEquals("10.0.0.5", resolver.resolve(malformed));
        assertEquals("10.0.0.5", resolver.resolve(shortChain));
    }

    @Test
    void rejectsInvalidTrustedProxyCounts() {
        assertThrows(IllegalArgumentException.class, () ->
                new TrustedProxyClientAddressResolver(-1));
        assertThrows(IllegalArgumentException.class, () ->
                new TrustedProxyClientAddressResolver(17));
    }
}
