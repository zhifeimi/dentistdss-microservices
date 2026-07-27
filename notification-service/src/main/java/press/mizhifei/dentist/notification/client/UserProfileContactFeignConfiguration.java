package press.mizhifei.dentist.notification.client;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import press.mizhifei.dentist.security.servicetoken.ServiceAuthProperties;
import press.mizhifei.dentist.security.servicetoken.ServiceTokenRequestInterceptor;

/**
 * Per-client Feign configuration for {@link UserProfileContactClient}.
 * Deliberately NOT annotated {@code @Configuration}: it is applied only to
 * this client through {@code @FeignClient(configuration = ...)}, so its
 * interceptor never applies to other Feign clients in this service.
 *
 * <p>Attaches a fresh 30-second credential with audience
 * {@code user-profile-service} and scope {@code user:contact:read} to each
 * call. With issuer keys unconfigured (local dev) the interceptor stays
 * dormant and the call proceeds without the header, exactly as before.</p>
 */
public class UserProfileContactFeignConfiguration {

    @Bean
    RequestInterceptor userProfileContactServiceTokenInterceptor(
            ServiceAuthProperties serviceAuthProperties) {
        return new ServiceTokenRequestInterceptor(
                serviceAuthProperties.issuer(),
                "user-profile-service",
                "user:contact:read");
    }
}
