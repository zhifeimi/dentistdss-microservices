package press.mizhifei.dentist.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DentistDssJwtSecurityTest {

    private static final String ISSUER = "https://api.example.test";
    private static final String AUDIENCE = "dentistdss-api";

    private final OAuth2TokenValidator<Jwt> validator = DentistDssJwtValidators.create(ISSUER, AUDIENCE);

    @Test
    void acceptsSignedAccessTokenClaimsRequiredByServices() {
        assertFalse(validator.validate(jwt(AUDIENCE, "access", "token-id", "42")).hasErrors());
    }

    @Test
    void rejectsWrongIssuerAudienceRefreshTokenMissingJtiAndInvalidSubject() {
        assertTrue(validator.validate(jwt(
                "https://other-issuer.example", AUDIENCE, "access", "token-id", "42")).hasErrors());
        assertTrue(validator.validate(jwt("other-api", "access", "token-id", "42")).hasErrors());
        assertTrue(validator.validate(jwt(AUDIENCE, "refresh", "token-id", "42")).hasErrors());
        assertTrue(validator.validate(jwt(AUDIENCE, "access", null, "42")).hasErrors());
        assertTrue(validator.validate(jwt(AUDIENCE, "access", "token-id", null)).hasErrors());
        assertTrue(validator.validate(jwt(AUDIENCE, "access", "token-id", "user-42")).hasErrors());
        assertTrue(validator.validate(jwt(AUDIENCE, "access", "token-id", "0")).hasErrors());
    }

    @Test
    void rejectsMissingOrMalformedAccessTokenStateClaims() {
        assertTrue(validator.validate(jwtWithStateClaims(null, "family-1")).hasErrors());
        assertTrue(validator.validate(jwtWithStateClaims(0L, "family-1")).hasErrors());
        assertTrue(validator.validate(jwtWithStateClaims(-1L, "family-1")).hasErrors());
        assertTrue(validator.validate(jwtWithStateClaims(1.5d, "family-1")).hasErrors());
        assertTrue(validator.validate(jwtWithStateClaims("1", "family-1")).hasErrors());
        assertTrue(validator.validate(jwtWithStateClaims(1L, null)).hasErrors());
        assertTrue(validator.validate(jwtWithStateClaims(1L, "  ")).hasErrors());
        assertTrue(validator.validate(jwtWithStateClaims(1L, 99L)).hasErrors());
    }

    @Test
    void convertsRolesToSpringRoleAuthorities() {
        Authentication authentication = JwtAuthorityConverters.rolesConverter().convert(
                jwt(AUDIENCE, "access", "token-id", "42"));

        assertEquals("42", authentication.getName());
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SYSTEM_ADMIN")));
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_DENTIST")));
    }

    @Test
    void createsTypedUserFromVerifiedClaims() {
        AuthenticatedUser user = AuthenticatedUser.from(jwt(AUDIENCE, "access", "token-id", "42"));

        assertEquals("42", user.userId());
        assertEquals(42L, user.requiredNumericUserId());
        assertEquals("admin@example.com", user.email());
        assertEquals(9L, user.clinicId());
        assertTrue(user.hasRole("SYSTEM_ADMIN"));
        assertFalse(user.hasRole("PATIENT"));
    }

    @Test
    void typedUserAllowsMissingClinicForSystemUsers() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject("1")
                .claim("jti", "token-id")
                .issuedAt(Instant.now().minusSeconds(5))
                .notBefore(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("tokenType", "access")
                .claim("roles", List.of("SYSTEM_ADMIN"))
                .build();

        assertNull(AuthenticatedUser.from(jwt).clinicId());
    }

    private Jwt jwt(String audience, String tokenType, String tokenId, String subject) {
        return jwt(ISSUER, audience, tokenType, tokenId, subject);
    }

    private Jwt jwt(
            String issuer,
            String audience,
            String tokenType,
            String tokenId,
            String subject) {
        Jwt.Builder builder = baseJwt(issuer, audience, tokenType, tokenId, subject)
                .claim("securityVersion", 1L)
                .claim("sessionFamilyId", "family-1");
        return builder.build();
    }

    private Jwt jwtWithStateClaims(Object securityVersion, Object sessionFamilyId) {
        Jwt.Builder builder = baseJwt(ISSUER, AUDIENCE, "access", "token-id", "42");
        if (securityVersion != null) {
            builder.claim("securityVersion", securityVersion);
        }
        if (sessionFamilyId != null) {
            builder.claim("sessionFamilyId", sessionFamilyId);
        }
        return builder.build();
    }

    private Jwt.Builder baseJwt(
            String issuer,
            String audience,
            String tokenType,
            String tokenId,
            String subject) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuer(issuer)
                .audience(List.of(audience))
                .issuedAt(Instant.now().minusSeconds(5))
                .notBefore(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("tokenType", tokenType)
                .claim("email", "admin@example.com")
                .claim("roles", List.of("SYSTEM_ADMIN", "DENTIST"))
                .claim("clinicId", 9L);
        if (tokenId != null) {
            builder.claim("jti", tokenId);
        }
        if (subject != null) {
            builder.subject(subject);
        }
        return builder;
    }
}
