package press.mizhifei.dentist.auth.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import press.mizhifei.dentist.auth.dto.AuditEntryRequest;

/**
 * Audit ingestion client. Every call carries a fresh 30-second service
 * credential (audience {@code audit-service}, scope {@code audit:ingest})
 * minted by {@link AuditFeignConfiguration}; audit-service attributes the
 * entry's actor to this service's verified subject, never to a caller-
 * supplied field.
 */
@FeignClient(name = "audit-service", path = "/audit",
        configuration = AuditFeignConfiguration.class)
public interface AuditClient {

    @PostMapping
    ResponseEntity<String> record(@RequestBody AuditEntryRequest request);
}
