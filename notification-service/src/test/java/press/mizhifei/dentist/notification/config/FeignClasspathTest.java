package press.mizhifei.dentist.notification.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClientsConfiguration;

/**
 * OpenFeign's default client configuration declares beans typed on Boot's
 * http-converter module, which WebFlux services do not pull in. Without it,
 * Spring's introspection of this class throws NoClassDefFoundError and the
 * service fails to start.
 */
class FeignClasspathTest {

    @Test
    void feignClientConfigurationResolvesOnTheClasspath() {
        assertDoesNotThrow(() -> {
            for (Method method : FeignClientsConfiguration.class.getDeclaredMethods()) {
                method.getGenericReturnType().getTypeName();
                for (java.lang.reflect.Type type : method.getGenericParameterTypes()) {
                    type.getTypeName();
                }
            }
        });
    }
}
