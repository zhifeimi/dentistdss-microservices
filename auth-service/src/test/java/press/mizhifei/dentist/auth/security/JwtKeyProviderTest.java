package press.mizhifei.dentist.auth.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtKeyProviderTest {

    @Test
    void rejectsMissingKeysInProduction() {
        assertThrows(
            IllegalStateException.class,
            () -> new JwtKeyProvider("", "", "test-key", "prod")
        );
    }

    @Test
    void permitsEphemeralKeysOutsideProduction() {
        JwtKeyProvider provider = new JwtKeyProvider("", "", "test-key", "test");

        assertNotNull(provider.getPrivateKey());
        assertNotNull(provider.getPublicKey());
    }
}
