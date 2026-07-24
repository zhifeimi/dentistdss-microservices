package press.mizhifei.dentist.auth.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtKeyProvider jwtKeyProvider;

    @Value("${jwt.expiration}")
    private long jwtExpirationInMs;

    @Value("${jwt.issuer:https://api.mizhifei.press}")
    private String issuer;

    @Value("${jwt.audience:dentistdss-api}")
    private String audience;

    public String generateToken(
            Authentication authentication,
            String sessionFamilyId) {
        if (!StringUtils.hasText(sessionFamilyId)) {
            throw new IllegalArgumentException("Session family ID is required");
        }

        UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();
        if (userPrincipal.getId() == null
                || userPrincipal.getId() <= 0
                || userPrincipal.getSecurityVersion() <= 0) {
            throw new IllegalArgumentException("Positive user ID and security version are required");
        }
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusMillis(jwtExpirationInMs);
        List<String> authorities = userPrincipal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(userPrincipal.getId().toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(issuedAt))
                .notBeforeTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("email", userPrincipal.getEmail())
                .claim("roles", authorities)
                .claim("securityVersion", userPrincipal.getSecurityVersion())
                .claim("sessionFamilyId", sessionFamilyId)
                .claim("tokenType", "access");

        if (userPrincipal.getClinicId() != null) {
            claims.claim("clinicId", userPrincipal.getClinicId());
        }

        try {
            SignedJWT signedJwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(jwtKeyProvider.getKeyId())
                            .build(),
                    claims.build());
            signedJwt.sign(new RSASSASigner((RSAPrivateKey) jwtKeyProvider.getPrivateKey()));
            return signedJwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign access token", ex);
        }
    }

    public String getUserIdFromJWT(String token) {
        return validatedClaims(token).getSubject();
    }

    public AccessTokenClaims getAccessTokenClaims(String token) {
        JWTClaimsSet claims = validatedClaims(token);
        return new AccessTokenClaims(
                positiveLong(claims.getSubject(), "subject"),
                positiveLongClaim(claims, "securityVersion"),
                stringClaim(claims, "sessionFamilyId"));
    }

    public boolean validateToken(String token) {
        try {
            validatedClaims(token);
            return true;
        } catch (RuntimeException ex) {
            log.debug("Rejected invalid access token");
            return false;
        }
    }

    public String getEmailFromJWT(String token) {
        return stringClaim(validatedClaims(token), "email");
    }

    public String getRolesFromJWT(String token) {
        Object roles = validatedClaims(token).getClaim("roles");
        if (roles instanceof List<?> roleList) {
            return roleList.stream().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("");
        }
        return roles == null ? "" : roles.toString();
    }

    public String getClinicIdFromJWT(String token) {
        Object clinicId = validatedClaims(token).getClaim("clinicId");
        return clinicId == null ? null : clinicId.toString();
    }

    private JWTClaimsSet validatedClaims(String token) {
        try {
            SignedJWT signedJwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.RS256.equals(signedJwt.getHeader().getAlgorithm())) {
                throw new IllegalArgumentException("Unexpected token algorithm");
            }
            if (!jwtKeyProvider.getKeyId().equals(signedJwt.getHeader().getKeyID())) {
                throw new IllegalArgumentException("Unknown signing key");
            }
            if (!signedJwt.verify(new RSASSAVerifier((RSAPublicKey) jwtKeyProvider.getPublicKey()))) {
                throw new IllegalArgumentException("Invalid token signature");
            }

            JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
            Instant now = Instant.now();
            if (!issuer.equals(claims.getIssuer()) || !claims.getAudience().contains(audience)) {
                throw new IllegalArgumentException("Invalid token issuer or audience");
            }
            if (claims.getExpirationTime() == null || !claims.getExpirationTime().toInstant().isAfter(now)) {
                throw new IllegalArgumentException("Expired token");
            }
            if (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now)) {
                throw new IllegalArgumentException("Token is not active");
            }
            if (!"access".equals(stringClaim(claims, "tokenType"))
                    || claims.getJWTID() == null
                    || !StringUtils.hasText(stringClaim(claims, "sessionFamilyId"))) {
                throw new IllegalArgumentException("Invalid token type or session family");
            }
            positiveLong(claims.getSubject(), "subject");
            positiveLongClaim(claims, "securityVersion");
            return claims;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid access token", ex);
        }
    }

    private long positiveLongClaim(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return positiveLong(value == null ? null : value.toString(), name);
    }

    private long positiveLong(String value, String name) {
        if (value == null || !value.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("Invalid positive numeric " + name);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid positive numeric " + name, ex);
        }
    }

    private String stringClaim(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value == null ? null : value.toString();
    }

    public record AccessTokenClaims(
            long userId,
            long securityVersion,
            String sessionFamilyId) {
    }
}
