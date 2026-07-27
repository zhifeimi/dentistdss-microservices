package press.mizhifei.dentist.security.servicetoken;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feign interceptor attaching a fresh service credential to every call of one
 * client (audience + scope fixed per bean). Credentials live 30 seconds, so a
 * new one is minted per request — no caching, no replay surface across calls.
 *
 * <p>Dormant by design: when issuer keys are not configured (local dev), the
 * interceptor omits the header instead of failing the call; the target then
 * rejects the unauthenticated request exactly as it does today, keeping
 * local behavior unchanged until keys are provisioned.</p>
 */
public final class ServiceTokenRequestInterceptor implements RequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenRequestInterceptor.class);

    private final ServiceTokenIssuer issuer;
    private final String audience;
    private final String scope;

    public ServiceTokenRequestInterceptor(
            ServiceTokenIssuer issuer,
            String audience,
            String scope) {
        this.issuer = issuer;
        this.audience = audience;
        this.scope = scope;
    }

    @Override
    public void apply(RequestTemplate template) {
        try {
            template.header(
                    ServiceTokenConstants.HEADER_NAME,
                    ServiceTokenConstants.BEARER_PREFIX + issuer.issue(audience, scope));
        } catch (ServiceTokenConfigurationException error) {
            log.debug("Service credential unavailable for {} (scope {}): {}",
                    audience, scope, error.getMessage());
        }
    }
}
