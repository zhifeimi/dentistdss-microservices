package press.mizhifei.dentist.clinic.directory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code public-directory.*}: the public clinic directory and its frontend
 * rebuild trigger.
 *
 * @param apiBasePath  external path of the directory behind the gateway, used
 *                     for the {@code Location} of old-slug redirects
 * @param cacheMaxAge  {@code Cache-Control: public, max-age} of responses
 * @param sync         the scheduled reconciler (slug assignment + rebuild trigger)
 * @param deployHook   the frontend's Vercel Deploy Hook
 */
@ConfigurationProperties("public-directory")
public record PublicDirectoryProperties(
        @DefaultValue("/api/public/clinics") String apiBasePath,
        @DefaultValue("PT5M") Duration cacheMaxAge,
        @DefaultValue Sync sync,
        @DefaultValue DeployHook deployHook) {

    /**
     * @param enabled      run the reconciler on this instance
     * @param interval     delay between reconciler runs
     * @param initialDelay delay before the first run after start-up
     */
    public record Sync(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT5M") Duration interval,
            @DefaultValue("PT1M") Duration initialDelay) {
    }

    /**
     * @param url            the Deploy Hook URL — a secret (anyone holding it can
     *                       trigger deployments); empty disables the trigger
     * @param minInterval    at most one attempt per this interval, including failures and unknown outcomes
     * @param retryInterval  failed/unknown attempt cooldown; may extend but never shorten minInterval
     * @param connectTimeout connect timeout of the hook call
     * @param readTimeout    response timeout of the hook call
     */
    public record DeployHook(
            @DefaultValue("") String url,
            @DefaultValue("PT1H") Duration minInterval,
            @DefaultValue("PT10M") Duration retryInterval,
            @DefaultValue("PT5S") Duration connectTimeout,
            @DefaultValue("PT10S") Duration readTimeout) {

        /** Never print the secret URL (startup logs, configprops, error messages). */
        @Override
        public String toString() {
            return "DeployHook[url=" + (url == null || url.isBlank() ? "<unset>" : "<redacted>")
                    + ", minInterval=" + minInterval + ", retryInterval=" + retryInterval
                    + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
        }
    }
}
