package press.mizhifei.dentist.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;
import press.mizhifei.dentist.security.ServletJwtSecurityAutoConfiguration;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
// auth-service ISSUES user JWTs; it never validates them as a resource
// server and intentionally has no jwk-set-uri/jwt resource-server
// configuration. The shared servlet JWT resource-server autoconfiguration
// (brought in by security-common for the outbound service-token support)
// would demand those decoder inputs at startup, so it is excluded here.
// The service's own security filter chain is defined locally.
@SpringBootApplication(exclude = ServletJwtSecurityAutoConfiguration.class)
@EnableDiscoveryClient
@EnableFeignClients
@EnableAsync
@EnableAspectJAutoProxy
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
