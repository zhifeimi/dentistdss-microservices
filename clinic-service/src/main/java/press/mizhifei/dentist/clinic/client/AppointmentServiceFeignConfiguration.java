package press.mizhifei.dentist.clinic.client;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class AppointmentServiceFeignConfiguration {

    @Bean
    RequestInterceptor appointmentAuthorizationRelayInterceptor() {
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
