package press.mizhifei.dentist.security.servicetoken;

/**
 * Raised when service credentials are requested but the issuer key material is
 * not configured. Callers must fail closed on this exception: interceptors
 * swallow it (omitting the header so the target rejects the unauthenticated
 * call) rather than forwarding a credential-less request that a misconfigured
 * target might accidentally accept.
 */
public class ServiceTokenConfigurationException extends RuntimeException {

    public ServiceTokenConfigurationException(String message) {
        super(message);
    }
}
