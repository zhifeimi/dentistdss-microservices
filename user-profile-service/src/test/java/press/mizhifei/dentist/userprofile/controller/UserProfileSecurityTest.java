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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import press.mizhifei.dentist.security.RedisAccessTokenJwtDecoder;
import press.mizhifei.dentist.security.ServletBearerTokenFailureHandler;
import press.mizhifei.dentist.security.ServletJwtResourceServerCustomizer;
import press.mizhifei.dentist.userprofile.config.UserProfileSecurityConfig;
import press.mizhifei.dentist.userprofile.dto.ApiResponse;
import press.mizhifei.dentist.userprofile.dto.PatientResponse;
import press.mizhifei.dentist.userprofile.dto.UserResponse;
import press.mizhifei.dentist.userprofile.service.DentistService;
import press.mizhifei.dentist.userprofile.service.PatientService;
import press.mizhifei.dentist.userprofile.service.UserService;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        UserProfileSecurityTest.TestConfig.class,
        UserProfileSecurityConfig.class
})
@TestPropertySource(properties = "springdoc.api-docs.enabled=false")
class UserProfileSecurityTest {

    private static final String AUDIENCE = "user-profile-service";
    private static final String NOTIFICATION_KID = "notification-service-2026-07";
    private static final RSAKey NOTIFICATION_KEY = createKey(NOTIFICATION_KID);
    private static final RSAKey ROGUE_KEY = createKey("rogue-kid");

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private UserService userService;

    @Autowired
    private PatientService patientService;

    @Autowired
    private DentistService dentistService;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void serviceAuthProperties(DynamicPropertyRegistry registry) {
        registry.add("app.security.service-auth.audience", () -> AUDIENCE);
        registry.add("app.security.service-auth.trusted-keys.notification.key-id",
                () -> NOTIFICATION_KID);
        registry.add("app.security.service-auth.trusted-keys.notification.public-key",
                () -> publicKeyPem(NOTIFICATION_KEY));
        registry.add("app.security.service-auth.trusted-keys.notification.subject",
                () -> "notification-service");
        registry.add("app.security.service-auth.trusted-keys.notification.scopes[0]",
                () -> "user:contact:read");
    }

    @BeforeEach
    void setUp() {
        reset(userService, patientService, dentistService);
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .apply(springSecurity())
                .build();
    }

    // ---- boundary: forged headers and missing tokens ----

    @Test
    void forgedIdentityHeadersCannotAuthenticateUserRoute() throws Exception {
        mockMvc.perform(get("/user/{id}/details", 42L)
                        .header("X-User-ID", "42")
                        .header("X-User-Roles", "SYSTEM_ADMIN")
                        .header("X-Clinic-ID", "7"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void patientListRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/patient/list/all"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(patientService);
    }

    @Test
    void dentistDirectoryRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/dentist/clinic/{clinicId}", 7L))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(dentistService);
    }

    // ---- role gates ----

    @Test
    void clinicAdminCannotListAllUsers() throws Exception {
        mockMvc.perform(get("/user/list/all").with(clinicAdminJwt(7L)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    void systemAdminListsAllUsers() throws Exception {
        when(userService.listAllUsers()).thenReturn(List.of());

        mockMvc.perform(get("/user/list/all").with(systemAdminJwt()))
                .andExpect(status().isOk());

        verify(userService).listAllUsers();
    }

    @Test
    void patientCannotLookupDetailsByEmail() throws Exception {
        mockMvc.perform(get("/user/email/{email}/details", "patient@example.test")
                        .with(patientJwt(42L)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    void patientCannotRegisterPatients() throws Exception {
        mockMvc.perform(post("/patient/add")
                        .with(patientJwt(42L))
                        .contentType("application/json")
                        .content("{\"firstName\":\"Walk\",\"lastName\":\"In\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(patientService);
    }

    @Test
    void receptionistRegistersPatients() throws Exception {
        when(patientService.createPatient(any()))
                .thenReturn(PatientResponse.builder().id(9L).build());

        mockMvc.perform(post("/patient/add")
                        .with(receptionistJwt(7L))
                        .contentType("application/json")
                        .content("{\"firstName\":\"Walk\",\"lastName\":\"In\"}"))
                .andExpect(status().isOk());

        verify(patientService).createPatient(any());
    }

    @Test
    void patientCannotReadPatientRecords() throws Exception {
        mockMvc.perform(get("/patient/{id}", 9L).with(patientJwt(9L)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(patientService);
    }

    @Test
    void dentistReadsPatientRecords() throws Exception {
        when(patientService.getPatientById(9L))
                .thenReturn(PatientResponse.builder().id(9L).build());

        mockMvc.perform(get("/patient/{id}", 9L).with(dentistJwt(7L)))
                .andExpect(status().isOk());

        verify(patientService).getPatientById(9L);
    }

    // ---- ownership rules ----

    @Test
    void userReadsOwnDetails() throws Exception {
        when(userService.getUserDetails(42L))
                .thenReturn(ApiResponse.success(null));

        mockMvc.perform(get("/user/{id}/details", 42L).with(patientJwt(42L)))
                .andExpect(status().isOk());

        verify(userService).getUserDetails(42L);
    }

    @Test
    void userCannotReadAnotherUsersDetails() throws Exception {
        mockMvc.perform(get("/user/{id}/details", 43L).with(patientJwt(42L)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    void userCannotReadAnotherUsersEmail() throws Exception {
        mockMvc.perform(get("/user/{id}/email", 43L).with(patientJwt(42L)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    void userUpdatesOwnProfile() throws Exception {
        when(userService.updateUserProfile(eq(42L), any()))
                .thenReturn(ApiResponse.success(null));

        mockMvc.perform(put("/user/{userId}", 42L)
                        .with(patientJwt(42L))
                        .contentType("application/json")
                        .content("{\"firstName\":\"Renamed\"}"))
                .andExpect(status().isOk());

        verify(userService).updateUserProfile(eq(42L), any());
    }

    @Test
    void userCannotUpdateAnotherUsersProfile() throws Exception {
        mockMvc.perform(put("/user/{userId}", 43L)
                        .with(patientJwt(42L))
                        .contentType("application/json")
                        .content("{\"firstName\":\"Renamed\"}"))
                .andExpect(status().isForbidden());

        verify(userService, never()).updateUserProfile(any(), any());
    }

    @Test
    void systemAdminUpdatesAnotherUsersProfile() throws Exception {
        when(userService.updateUserProfile(eq(43L), any()))
                .thenReturn(ApiResponse.success(null));

        mockMvc.perform(put("/user/{userId}", 43L)
                        .with(systemAdminJwt())
                        .contentType("application/json")
                        .content("{\"firstName\":\"Renamed\"}"))
                .andExpect(status().isOk());

        verify(userService).updateUserProfile(eq(43L), any());
    }

    // ---- booking-flow reads open to any authenticated user ----

    @Test
    void authenticatedUserReadsDentistDirectoryAndNames() throws Exception {
        when(dentistService.getDentistsByClinic(7L)).thenReturn(List.of());
        when(userService.getUserFullName(84L)).thenReturn("Dr. Smith");
        when(userService.getClinicDentists(7L)).thenReturn(List.of());

        mockMvc.perform(get("/dentist/clinic/{clinicId}", 7L).with(patientJwt(42L)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/user/{id}/name", 84L).with(patientJwt(42L)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/user/clinic/{clinicId}/dentists", 7L).with(patientJwt(42L)))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedUnknownRouteIsDenied() throws Exception {
        mockMvc.perform(get("/not-a-user-route").with(systemAdminJwt()))
                .andExpect(status().isForbidden());
    }

    // ---- service credentials: contact reads (user:contact:read) ----

    @Test
    void verifiedServiceCredentialReadsUserEmail() throws Exception {
        when(userService.getUserEmail(42L)).thenReturn("patient@example.test");

        mockMvc.perform(get("/user/{id}/email", 42L)
                        .header("X-Service-Authorization",
                                "Bearer " + validContactReadCredential()))
                .andExpect(status().isOk())
                .andExpect(content().string("patient@example.test"));

        verify(userService).getUserEmail(42L);
    }

    @Test
    void verifiedServiceCredentialReadsUserFullName() throws Exception {
        when(userService.getUserFullName(42L)).thenReturn("Pat Ient");

        mockMvc.perform(get("/user/{id}/name", 42L)
                        .header("X-Service-Authorization",
                                "Bearer " + validContactReadCredential()))
                .andExpect(status().isOk())
                .andExpect(content().string("Pat Ient"));

        verify(userService).getUserFullName(42L);
    }

    @Test
    void serviceCredentialIsIgnoredOutsideTheContactReads() throws Exception {
        // /user/list/all is not a contact-read route: the service filter does
        // not match, so the credential is ignored and the request falls
        // through to the user-JWT chain, which rejects it (no Authorization).
        mockMvc.perform(get("/user/list/all")
                        .header("X-Service-Authorization",
                                "Bearer " + validContactReadCredential()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void serviceCredentialCannotBypassOwnershipOnUserDetails() throws Exception {
        mockMvc.perform(get("/user/{id}/details", 42L)
                        .header("X-Service-Authorization",
                                "Bearer " + validContactReadCredential()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsCredentialWithScopeBeyondTheKeysGrant() throws Exception {
        // notification's key is only granted user:contact:read
        mockMvc.perform(get("/user/{id}/email", 42L)
                        .header("X-Service-Authorization", "Bearer " + credential(
                                NOTIFICATION_KEY, NOTIFICATION_KID,
                                "notification-service", "notification:send")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsCredentialForTheWrongAudience() throws Exception {
        mockMvc.perform(get("/user/{id}/email", 42L)
                        .header("X-Service-Authorization", "Bearer " + credential(
                                NOTIFICATION_KEY, NOTIFICATION_KID,
                                "notification-service", "user:contact:read",
                                "audit-service")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsCredentialWithAnUnknownKid() throws Exception {
        mockMvc.perform(get("/user/{id}/email", 42L)
                        .header("X-Service-Authorization", "Bearer " + credential(
                                ROGUE_KEY, "rogue-kid",
                                "notification-service", "user:contact:read")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsCredentialSignedByAnotherKeyUnderATrustedKid() throws Exception {
        // rogue key claims notification's kid: signature fails against the key
        // bound to that kid
        mockMvc.perform(get("/user/{id}/email", 42L)
                        .header("X-Service-Authorization", "Bearer " + credential(
                                ROGUE_KEY, NOTIFICATION_KID,
                                "notification-service", "user:contact:read")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void rejectsCredentialWithAForgedSubjectUnderItsOwnKid() throws Exception {
        // notification's key cannot impersonate auth-service: subject is bound
        // to the kid
        mockMvc.perform(get("/user/{id}/email", 42L)
                        .header("X-Service-Authorization", "Bearer " + credential(
                                NOTIFICATION_KEY, NOTIFICATION_KID,
                                "auth-service", "user:contact:read")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    private static String validContactReadCredential() throws Exception {
        return credential(NOTIFICATION_KEY, NOTIFICATION_KID,
                "notification-service", "user:contact:read");
    }

    private static String credential(RSAKey signingKey, String kid, String subject,
            String scope) throws Exception {
        return credential(signingKey, kid, subject, scope, AUDIENCE);
    }

    private static String credential(RSAKey signingKey, String kid, String subject,
            String scope, String audience) throws Exception {
        Instant now = Instant.now().minusSeconds(1);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://dentistdss.internal/services")
                .subject(subject)
                .audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(30)))
                .claim("tokenType", "service")
                .claim("scope", scope)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(kid)
                        .build(),
                claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private static RSAKey createKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static String publicKeyPem(RSAKey key) {
        try {
            byte[] encoded = ((RSAPublicKey) key.toRSAPublicKey()).getEncoded();
            return "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[] {'\n'})
                            .encodeToString(encoded)
                    + "\n-----END PUBLIC KEY-----";
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private RequestPostProcessor patientJwt(Long userId) {
        return jwt()
                .jwt(token -> token
                        .subject(String.valueOf(userId))
                        .claim("roles", List.of("PATIENT")))
                .authorities(new SimpleGrantedAuthority("ROLE_PATIENT"));
    }

    private RequestPostProcessor dentistJwt(Long clinicId) {
        return jwt()
                .jwt(token -> token
                        .subject("84")
                        .claim("roles", List.of("DENTIST"))
                        .claim("clinicId", clinicId))
                .authorities(new SimpleGrantedAuthority("ROLE_DENTIST"));
    }

    private RequestPostProcessor receptionistJwt(Long clinicId) {
        return jwt()
                .jwt(token -> token
                        .subject("66")
                        .claim("roles", List.of("RECEPTIONIST"))
                        .claim("clinicId", clinicId))
                .authorities(new SimpleGrantedAuthority("ROLE_RECEPTIONIST"));
    }

    private RequestPostProcessor clinicAdminJwt(Long clinicId) {
        return jwt()
                .jwt(token -> token
                        .subject("55")
                        .claim("roles", List.of("CLINIC_ADMIN"))
                        .claim("clinicId", clinicId))
                .authorities(new SimpleGrantedAuthority("ROLE_CLINIC_ADMIN"));
    }

    private RequestPostProcessor systemAdminJwt() {
        return jwt()
                .jwt(token -> token
                        .subject("1")
                        .claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        PatientService patientService() {
            return mock(PatientService.class);
        }

        @Bean
        DentistService dentistService() {
            return mock(DentistService.class);
        }

        @Bean
        UserController userController(UserService userService) {
            return new UserController(userService);
        }

        @Bean
        PatientController patientController(PatientService patientService) {
            return new PatientController(patientService);
        }

        @Bean
        DentistController dentistController(DentistService dentistService) {
            return new DentistController(dentistService);
        }

        @Bean
        ServletBearerTokenFailureHandler servletBearerTokenFailureHandler() {
            return new ServletBearerTokenFailureHandler();
        }

        @Bean
        ServletJwtResourceServerCustomizer servletJwtResourceServerCustomizer(
                ServletBearerTokenFailureHandler servletBearerTokenFailureHandler) {
            return new ServletJwtResourceServerCustomizer(
                    mock(RedisAccessTokenJwtDecoder.class),
                    new JwtAuthenticationConverter(),
                    servletBearerTokenFailureHandler);
        }
    }
}
