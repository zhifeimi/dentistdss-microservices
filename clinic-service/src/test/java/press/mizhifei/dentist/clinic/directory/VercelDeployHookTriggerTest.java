package press.mizhifei.dentist.clinic.directory;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VercelDeployHookTriggerTest {

    private static final String SECRET_PATH = "/v1/integrations/deploy/prj_secret/hook-secret";

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsToTheHookAndAcceptsSuccess() throws IOException {
        String url = startServer(201);

        new VercelDeployHookTrigger(properties(url)).trigger();

        assertThat(requests).containsExactly("POST " + SECRET_PATH);
    }

    @Test
    void errorStatusFailsWithoutTheUrl() throws IOException {
        String url = startServer(500);
        VercelDeployHookTrigger trigger = new VercelDeployHookTrigger(properties(url));

        assertThatThrownBy(trigger::trigger)
                .isInstanceOf(FrontendRebuildTrigger.RebuildTriggerException.class)
                .hasMessage("Deploy hook answered HTTP 500");
    }

    @Test
    void connectionFailureFailsWithoutTheUrl() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        VercelDeployHookTrigger trigger = new VercelDeployHookTrigger(
                properties("http://127.0.0.1:" + closedPort + SECRET_PATH));

        assertThatThrownBy(trigger::trigger)
                .isInstanceOf(FrontendRebuildTrigger.RebuildTriggerException.class)
                .hasMessageStartingWith("Deploy hook request failed: ")
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("127.0.0.1");
    }

    @Test
    void onlyHttpsUrlsEnableTheTrigger() {
        assertThat(new VercelDeployHookTrigger(properties("")).isConfigured()).isFalse();
        assertThat(new VercelDeployHookTrigger(properties("http://api.vercel.com/v1/x")).isConfigured()).isFalse();
        assertThat(new VercelDeployHookTrigger(properties("https://u:p@api.vercel.com/x")).isConfigured()).isFalse();
        assertThat(new VercelDeployHookTrigger(properties("not a url")).isConfigured()).isFalse();
        assertThat(new VercelDeployHookTrigger(
                properties("https://api.vercel.com/v1/integrations/deploy/prj_x/y")).isConfigured()).isTrue();
    }

    @Test
    void propertiesNeverPrintTheUrl() {
        assertThat(properties("https://api.vercel.com" + SECRET_PATH).toString())
                .doesNotContain("secret")
                .contains("<redacted>");
    }

    private String startServer(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + SECRET_PATH;
    }

    private static PublicDirectoryProperties properties(String url) {
        return new PublicDirectoryProperties(
                "/api/public/clinics", Duration.ofMinutes(5),
                new PublicDirectoryProperties.Sync(true, Duration.ofMinutes(5), Duration.ofMinutes(1)),
                new PublicDirectoryProperties.DeployHook(url, Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofSeconds(2), Duration.ofSeconds(2)));
    }
}
