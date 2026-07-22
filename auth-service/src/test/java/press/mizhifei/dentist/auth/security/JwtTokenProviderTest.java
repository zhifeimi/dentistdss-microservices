package press.mizhifei.dentist.auth.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {

    private JwtTokenProvider tokenProvider;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        JwtKeyProvider keyProvider = new JwtKeyProvider("", "", "test-key", "test");
        tokenProvider = new JwtTokenProvider(keyProvider);
        ReflectionTestUtils.setField(tokenProvider, "jwtExpirationInMs", 300_000L);
        ReflectionTestUtils.setField(tokenProvider, "issuer", "https://issuer.example");
        ReflectionTestUtils.setField(tokenProvider, "audience", "dentistdss-api");

        UserPrincipal principal = UserPrincipal.builder()
                .id(42L)
                .email("patient@example.com")
                .authorities(List.of(new SimpleGrantedAuthority("PATIENT")))
                .clinicId(7L)
                .securityVersion(3L)
                .enabled(true)
                .accountNonExpired(true)
                .credentialsNonExpired(true)
                .accountNonLocked(true)
                .build();
        authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                principal.getAuthorities());
    }

    @Test
    void signsStableSessionFamilyClaimWithUniqueTokenIds() throws Exception {
        String firstToken = tokenProvider.generateToken(authentication, "family-1");
        String secondToken = tokenProvider.generateToken(authentication, "family-1");
        JWTClaimsSet firstClaims = SignedJWT.parse(firstToken).getJWTClaimsSet();
        JWTClaimsSet secondClaims = SignedJWT.parse(secondToken).getJWTClaimsSet();

        assertEquals("family-1", firstClaims.getStringClaim("sessionFamilyId"));
        assertEquals("family-1", secondClaims.getStringClaim("sessionFamilyId"));
        assertEquals(3L, firstClaims.getLongClaim("securityVersion"));
        assertNotEquals(firstClaims.getJWTID(), secondClaims.getJWTID());
        assertEquals("42", tokenProvider.getUserIdFromJWT(firstToken));
        assertEquals(3L, tokenProvider.getAccessTokenClaims(firstToken).securityVersion());
        assertTrue(tokenProvider.validateToken(firstToken));
    }

    @Test
    void rejectsMissingSessionFamilyBeforeSigning() {
        assertThrows(IllegalArgumentException.class, () ->
                tokenProvider.generateToken(authentication, " "));
    }

    @Test
    void rejectsNonPositiveSecurityVersionBeforeSigning() {
        ((UserPrincipal) authentication.getPrincipal()).setSecurityVersion(0L);

        assertThrows(IllegalArgumentException.class, () ->
                tokenProvider.generateToken(authentication, "family-1"));
    }
}
