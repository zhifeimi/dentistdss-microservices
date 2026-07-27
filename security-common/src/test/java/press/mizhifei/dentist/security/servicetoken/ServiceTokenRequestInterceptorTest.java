package press.mizhifei.dentist.security.servicetoken;

import com.nimbusds.jose.jwk.RSAKey;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTokenRequestInterceptorTest {

    @Test
    void attachesAFreshCredentialPerCall() throws Exception {
        RSAKey key = ServiceTokenTestKeys.generateKey(ServiceTokenTestKeys.APPOINTMENT_KID);
        ServiceTokenIssuer issuer = new ServiceTokenIssuer(
                ServiceTokenTestKeys.privateKeyPem(key),
                ServiceTokenTestKeys.APPOINTMENT_KID,
                "appointment-service");
        ServiceTokenRequestInterceptor interceptor = new ServiceTokenRequestInterceptor(
                issuer, "notification-service", "notification:send");

        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        Collection<String> values = template.headers().get(ServiceTokenConstants.HEADER_NAME);
        assertNotNull(values);
        assertEquals(1, values.size());
        String header = values.iterator().next();
        assertTrue(header.startsWith(ServiceTokenConstants.BEARER_PREFIX));
        // Each call mints a fresh 30s credential (distinct jti).
        RequestTemplate second = new RequestTemplate();
        interceptor.apply(second);
        assertFalse(header.equals(
                second.headers().get(ServiceTokenConstants.HEADER_NAME).iterator().next()),
                "every call gets a new credential");
    }

    @Test
    void staysDormantWhenIssuerIsUnconfigured() {
        ServiceTokenRequestInterceptor interceptor = new ServiceTokenRequestInterceptor(
                new ServiceTokenIssuer("", "", "appointment-service"),
                "notification-service",
                "notification:send");

        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        assertFalse(template.headers().containsKey(ServiceTokenConstants.HEADER_NAME),
                "no credential header when keys are not provisioned");
    }
}
