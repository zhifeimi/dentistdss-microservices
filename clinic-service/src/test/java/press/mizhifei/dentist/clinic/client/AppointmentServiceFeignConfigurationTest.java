package press.mizhifei.dentist.clinic.client;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppointmentServiceFeignConfigurationTest {

    private final RequestInterceptor interceptor =
            new AppointmentServiceFeignConfiguration()
                    .appointmentAuthorizationRelayInterceptor();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void relaysExactInboundBearerAuthorization() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer signed-token");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        Collection<String> values = template.headers().get(
                HttpHeaders.AUTHORIZATION);
        assertEquals(1, values.size());
        assertEquals("Bearer signed-token", values.iterator().next());
    }

    @Test
    void doesNotRelayNonBearerAuthorization() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic credentials");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertFalse(template.headers().containsKey(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void doesNothingWithoutRequestContext() {
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertTrue(template.headers().isEmpty());
    }

    @Test
    void configurationIsScopedToAppointmentClient() {
        FeignClient annotation = AppointmentServiceClient.class
                .getAnnotation(FeignClient.class);

        assertEquals(
                AppointmentServiceFeignConfiguration.class,
                annotation.configuration()[0]);
    }
}
