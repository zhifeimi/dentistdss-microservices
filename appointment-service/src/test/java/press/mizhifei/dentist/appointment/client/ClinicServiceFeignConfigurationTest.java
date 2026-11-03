package press.mizhifei.dentist.appointment.client;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ClinicServiceFeignConfigurationTest {

    private final RequestInterceptor interceptor = new ClinicServiceFeignConfiguration()
            .clinicAuthorizationRelayInterceptor();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer signed-token", "bearer signed-token", "BEARER signed-token", "bEaReR signed-token"})
    void relaysBearerAuthorizationRegardlessOfSchemeCase(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        var values = template.headers().get(HttpHeaders.AUTHORIZATION);
        assertNotNull(values);
        assertEquals(1, values.size());
        assertEquals(authorization, values.iterator().next());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "Basic credentials", "BearerX signed-token"})
    void doesNotRelayNonBearerAuthorization(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertFalse(template.headers().containsKey(HttpHeaders.AUTHORIZATION));
    }
}
