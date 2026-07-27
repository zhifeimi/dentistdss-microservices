package press.mizhifei.dentist.appointment.client;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Relays the caller's bearer token to clinic-service so the downstream JWT
 * resource-server checks see the same verified user identity.
 */
public class ClinicServiceFeignConfiguration {

    @Bean
    RequestInterceptor clinicAuthorizationRelayInterceptor() {
        return template -> {
            if (!(RequestContextHolder.getRequestAttributes()
                    instanceof ServletRequestAttributes attributes)) {
                return;
            }
            HttpServletRequest request = attributes.getRequest();
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null
                    && !authorization.isBlank()
                    && authorization.startsWith("Bearer ")) {
                template.header(HttpHeaders.AUTHORIZATION, authorization);
            }
        };
    }
}
