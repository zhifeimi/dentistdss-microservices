package press.mizhifei.dentist.gateway.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a client address without trusting forwarded headers unless the
 * deployment explicitly configures the number of trusted proxy hops.
 */
@Component
public class TrustedProxyClientAddressResolver {

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final int MAX_FORWARDED_HEADER_LENGTH = 1024;
    private static final int MAX_FORWARDED_ADDRESSES = 16;

    private final int trustedProxyCount;

    public TrustedProxyClientAddressResolver(
            @Value("${app.security.anonymous-session-issuance.trusted-proxy-count:0}")
            int trustedProxyCount) {
        if (trustedProxyCount < 0 || trustedProxyCount > MAX_FORWARDED_ADDRESSES) {
            throw new IllegalArgumentException(
                    "Trusted proxy count must be between 0 and " + MAX_FORWARDED_ADDRESSES);
        }
        this.trustedProxyCount = trustedProxyCount;
    }

    public String resolve(ServerHttpRequest request) {
        String remoteAddress = remoteAddress(request);
        if (trustedProxyCount == 0) {
            return remoteAddress;
        }

        String forwardedFor = request.getHeaders().getFirst(FORWARDED_FOR_HEADER);
        if (forwardedFor == null || forwardedFor.isBlank()
                || forwardedFor.length() > MAX_FORWARDED_HEADER_LENGTH) {
            return remoteAddress;
        }

        String[] entries = forwardedFor.split(",");
        if (entries.length > MAX_FORWARDED_ADDRESSES) {
            return remoteAddress;
        }

        List<String> addresses = new ArrayList<>(entries.length);
        for (String entry : entries) {
            String address = canonicalLiteralAddress(entry);
            if (address == null) {
                return remoteAddress;
            }
            addresses.add(address);
        }

        int clientIndex = addresses.size() - trustedProxyCount;
        return clientIndex >= 0 ? addresses.get(clientIndex) : remoteAddress;
    }

    private String remoteAddress(ServerHttpRequest request) {
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress != null && remoteAddress.getAddress() != null
                ? remoteAddress.getAddress().getHostAddress()
                : "unknown";
    }

    private String canonicalLiteralAddress(String candidate) {
        String value = candidate.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }

        boolean possibleIpv4 = value.matches("[0-9.]+") && value.contains(".");
        boolean possibleIpv6 = value.matches("[0-9A-Fa-f:.]+") && value.contains(":");
        if (!possibleIpv4 && !possibleIpv6) {
            return null;
        }

        try {
            InetAddress address = InetAddress.getByName(value);
            if ((possibleIpv4 && address instanceof Inet4Address)
                    || (possibleIpv6 && address instanceof Inet6Address)) {
                return address.getHostAddress();
            }
            return null;
        } catch (UnknownHostException ex) {
            return null;
        }
    }
}
