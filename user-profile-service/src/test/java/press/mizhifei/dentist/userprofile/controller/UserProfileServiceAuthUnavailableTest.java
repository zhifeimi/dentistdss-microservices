package press.mizhifei.dentist.userprofile.controller;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import press.mizhifei.dentist.userprofile.config.UserProfileSecurityConfig;
import press.mizhifei.dentist.userprofile.service.UserService;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-closed behavior when no service credentials are configured (the trust
 * map is empty because the key material environment variables are unset):
 * contact reads carrying a service credential are rejected with a sanitized
 * 503 — never accepted.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        UserProfileSecurityTest.TestConfig.class,
        UserProfileSecurityConfig.class
})
@TestPropertySource(properties = "springdoc.api-docs.enabled=false")
class UserProfileServiceAuthUnavailableTest {

    private static final RSAKey SIGNING_KEY = createKey();

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private UserService userService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void rejectsContactReadWithSanitizedServiceUnavailableWhenNoKeysAreConfigured()
            throws Exception {
        mockMvc.perform(get("/user/{id}/email", 42L)
                        .header("X-Service-Authorization",
                                "Bearer " + wellFormedCredential()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"));

        verifyNoInteractions(userService);
    }

    private static String wellFormedCredential() throws Exception {
        Instant now = Instant.now().minusSeconds(1);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://dentistdss.internal/services")
                .subject("notification-service")
                .audience("user-profile-service")
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(30)))
                .claim("tokenType", "service")
                .claim("scope", "user:contact:read")
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID("notification-service-2026-07")
                        .build(),
                claims);
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        return jwt.serialize();
    }

    private static RSAKey createKey() {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID("notification-service-2026-07")
                    .generate();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
