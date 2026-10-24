package press.mizhifei.dentist.admin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

import org.junit.jupiter.api.Test;

import de.codecentric.boot.admin.server.config.AdminServerAutoConfiguration;
import de.codecentric.boot.admin.server.services.InstanceRegistry;

/**
 * Spring Boot Admin must match the Spring Boot minor line: SBA 4.1 references
 * Boot 4.1-only types (e.g. InetAddressFilter), so on Boot 4.0 introspecting
 * its auto-configuration throws NoClassDefFoundError and admin-server fails
 * to start.
 */
class AdminServerClasspathTest {

    @Test
    void adminServerAutoConfigurationResolvesAgainstThisBootVersion() {
        for (Class<?> type : new Class<?>[] {AdminServerAutoConfiguration.class, InstanceRegistry.class }) {
            assertDoesNotThrow(() -> {
                for (Method method : type.getDeclaredMethods()) {
                    method.getGenericReturnType().getTypeName();
                    for (Type parameter : method.getGenericParameterTypes()) {
                        parameter.getTypeName();
                    }
                }
                type.getDeclaredConstructors();
            }, type.getSimpleName());
        }
    }
}
