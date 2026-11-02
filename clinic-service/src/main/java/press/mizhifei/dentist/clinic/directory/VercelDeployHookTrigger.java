package press.mizhifei.dentist.clinic.directory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.util.Locale;

/**
 * Calls the frontend project's Vercel Deploy Hook ({@code POST}, no body).
 * The hook URL is a secret: it is never logged, and failure messages carry
 * only the HTTP status or the exception type — RestClient's I/O errors embed
 * the request URL, so their messages are dropped.
 */
@Slf4j
@Component
public class VercelDeployHookTrigger implements FrontendRebuildTrigger {

    private final URI hookUri;
    private final RestClient restClient;

    public VercelDeployHookTrigger(PublicDirectoryProperties properties) {
        PublicDirectoryProperties.DeployHook hook = properties.deployHook();
        this.hookUri = parse(hook.url());
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(hook.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                // Honour the standard JVM proxy settings (https.proxyHost, ...).
                .proxy(ProxySelector.getDefault())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(hook.readTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        if (hookUri == null) {
            log.info("Frontend rebuild trigger disabled: no deploy hook URL configured");
        } else {
            log.info("Frontend rebuild trigger enabled for host {}", hookUri.getHost());
        }
    }

    @Override
    public boolean isConfigured() {
        return hookUri != null;
    }

    @Override
    public void trigger() {
        if (hookUri == null) {
            throw new RebuildTriggerException("Deploy hook is not configured");
        }
        HttpStatusCode status;
        try {
            status = restClient.post()
                    .uri(hookUri)
                    .retrieve()
                    .onStatus(code -> true, (request, response) -> {
                        // Inspect the status below instead of throwing.
                    })
                    .toBodilessEntity()
                    .getStatusCode();
        } catch (RestClientException ex) {
            Throwable cause = ex.getMostSpecificCause();
            throw new RebuildTriggerException("Deploy hook request failed: " + cause.getClass().getSimpleName());
        }
        if (!status.is2xxSuccessful()) {
            throw new RebuildTriggerException("Deploy hook answered HTTP " + status.value());
        }
    }

    private static boolean isLoopback(String host) {
        return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
    }

    /** The hook URI, or {@code null} when unset or invalid (logged without the value). */
    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean secure = scheme.equals("https")
                    // Plain http only to this machine (local testing), never over a network.
                    || (scheme.equals("http") && isLoopback(uri.getHost()));
            if (!secure || uri.getHost() == null || uri.getRawUserInfo() != null) {
                log.error("Frontend rebuild trigger disabled: the deploy hook URL must be an https URL");
                return null;
            }
            return uri;
        } catch (URISyntaxException ex) {
            log.error("Frontend rebuild trigger disabled: the deploy hook URL is not a valid URI");
            return null;
        }
    }
}
