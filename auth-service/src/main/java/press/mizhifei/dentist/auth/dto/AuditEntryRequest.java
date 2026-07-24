package press.mizhifei.dentist.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Outbound audit ingestion payload, mirroring audit-service's request
 * contract. There is deliberately no {@code actor} field: audit-service
 * assigns the actor server-side from the verified service credential's
 * subject (this service's registered name), so callers can never attribute
 * events to another identity. {@code assertedUserId}/{@code assertedClinicId}
 * are caller-claimed context about who the event concerns — queryable, but
 * never trusted by audit-service for authorization.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuditEntryRequest {
    private String action;
    private String target;
    private Long assertedUserId;
    private Long assertedClinicId;
    private Map<String, Object> context;
}
