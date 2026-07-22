package press.mizhifei.dentist.audit.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import press.mizhifei.dentist.audit.dto.AuditEntryResponse;
import press.mizhifei.dentist.audit.service.AuditService;
import press.mizhifei.dentist.security.ReactiveJwtSecurityAutoConfiguration;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@WebFluxTest(AuditController.class)
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
class AuditControllerSecurityTest {

    @Autowired
    private ApplicationContext applicationContext;

    private WebTestClient webTestClient;

    @MockitoBean
    private AuditService auditService;

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
                .uri("/audit")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(auditService);
    }

    @Test
    void forgedRoleHeaderDoesNotAuthenticateRequest() {
        webTestClient.get()
                .uri("/audit")
                .header("X-User-Roles", "SYSTEM_ADMIN")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(auditService);
    }

    @Test
    void rejectsAuthenticatedUserWithoutSystemAdminRole() {
        webTestClient.mutateWith(mockJwt().authorities(
                        new SimpleGrantedAuthority("ROLE_PATIENT")))
                .get()
                .uri("/audit")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(auditService);
    }

    @Test
    void allowsSystemAdminToListAuditEntries() {
        when(auditService.listAll()).thenReturn(List.of());

        webTestClient.mutateWith(mockJwt().authorities(
                        new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .get()
                .uri("/audit")
                .exchange()
                .expectStatus().isOk();

        verify(auditService).listAll();
    }

    @Test
    void rejectsNonAdminAuditIngestion() {
        webTestClient.mutateWith(mockJwt().authorities(
                        new SimpleGrantedAuthority("ROLE_DENTIST")))
                .post()
                .uri("/audit")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "actor": "forged-user",
                          "action": "READ",
                          "target": "record-1"
                        }
                        """)
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(auditService);
    }

    @Test
    void temporarilyAllowsOnlySystemAdminAuditIngestion() {
        when(auditService.record(any())).thenReturn(
                AuditEntryResponse.builder().id("audit-1").build());

        webTestClient.mutateWith(mockJwt().authorities(
                        new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .post()
                .uri("/audit")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "actor": "temporary-admin",
                          "action": "READ",
                          "target": "record-1"
                        }
                        """)
                .exchange()
                .expectStatus().isOk();

        verify(auditService).record(any());
    }
}
