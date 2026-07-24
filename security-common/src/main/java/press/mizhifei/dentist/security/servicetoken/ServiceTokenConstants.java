package press.mizhifei.dentist.security.servicetoken;

import java.time.Duration;

/**
 * Shared contract for audience-scoped service credentials exchanged between
 * backend services over direct (Eureka-routed) calls that bypass the gateway.
 *
 * <p>Credentials are short-lived RS256 JWTs carried in {@link #HEADER_NAME}.
 * The calling service signs each credential with its own RSA key pair
 * ({@code sub} = calling service name), every target service validates the
 * signature against the specific caller keys it trusts, and audience plus
 * scope pin the credential to one endpoint family on one target. The gateway
 * strips {@link #HEADER_NAME} from browser traffic, so these credentials can
 * only originate inside the service network.</p>
 *
 * <p>This is the generalized successor of the gateway-to-GenAI credential
 * ({@code X-Gateway-Service-Authorization}); that older single-purpose token
 * keeps its own header, issuer, and classes.</p>
 */
public final class ServiceTokenConstants {

    /** Header carrying the service credential, e.g. {@code X-Service-Authorization: Bearer <jwt>}. */
    public static final String HEADER_NAME = "X-Service-Authorization";

    public static final String BEARER_PREFIX = "Bearer ";

    /** Shared issuer for all internal service credentials. */
    public static final String ISSUER = "https://dentistdss.internal/services";

    /** {@code tokenType} claim distinguishing service credentials from user access tokens. */
    public static final String TOKEN_TYPE = "service";

    /** Hard cap on credential lifetime; verifiers reject anything longer. */
    public static final Duration MAX_LIFETIME = Duration.ofSeconds(30);

    private ServiceTokenConstants() {
    }
}
