package press.mizhifei.dentist.appointment.client;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import press.mizhifei.dentist.security.servicetoken.ServiceAuthProperties;
import press.mizhifei.dentist.security.servicetoken.ServiceTokenRequestInterceptor;

/**
 * Per-client Feign configuration for {@link NotificationClient}.
 * Deliberately NOT annotated {@code @Configuration}: it is applied only to
 * this client through {@code @FeignClient(configuration = ...)}, so its
 * interceptor never applies to other Feign clients in this service.
 *
 * <p>Attaches a fresh 30-second credential with audience
 * {@code notification-service} and scope {@code notification:send} to each
 * call. With issuer keys unconfigured (local dev) the interceptor stays
 * dormant and the call proceeds without the header, exactly as before.</p>
 */
public class NotificationSendFeignConfiguration {

    @Bean
    RequestInterceptor notificationSendServiceTokenInterceptor(
            ServiceAuthProperties serviceAuthProperties) {
        return new ServiceTokenRequestInterceptor(
                serviceAuthProperties.issuer(),
                "notification-service",
                "notification:send");
    }
}
