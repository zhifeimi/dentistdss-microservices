package press.mizhifei.dentist.system.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import press.mizhifei.dentist.security.ReactiveJwtSecurityAutoConfiguration;
import press.mizhifei.dentist.system.service.SystemSettingService;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@WebFluxTest(SystemSettingController.class)
@ImportAutoConfiguration(ReactiveWebSecurityAutoConfiguration.class)
@Import(ReactiveJwtSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://auth.example/jwks",
        "jwt.issuer=https://issuer.example",
        "jwt.audience=dentistdss-api",
        "springdoc.api-docs.enabled=false",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class SystemSettingControllerSecurityTest {

    @Autowired
    private ApplicationContext applicationContext;

    private WebTestClient webTestClient;

    @MockitoBean
    private SystemSettingService systemSettingService;

    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void rejectsRequestWithoutBearerToken() {
        webTestClient.get()
                .uri("/system/setting")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(systemSettingService);
    }

    @Test
    void forgedRoleHeaderDoesNotAuthenticateRequest() {
        webTestClient.get()
                .uri("/system/setting")
                .header("X-User-Roles", "SYSTEM_ADMIN")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(systemSettingService);
    }

    @Test
    void rejectsAuthenticatedUserWithoutSystemAdminRole() {
        webTestClient.mutateWith(mockJwt().authorities(
                        new SimpleGrantedAuthority("ROLE_PATIENT")))
                .get()
                .uri("/system/setting")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(systemSettingService);
    }

    @Test
    void allowsSystemAdminToListSettings() {
        when(systemSettingService.listAll()).thenReturn(List.of());

        webTestClient.mutateWith(mockJwt().authorities(
                        new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .get()
                .uri("/system/setting")
                .exchange()
                .expectStatus().isOk();

        verify(systemSettingService).listAll();
    }
}
