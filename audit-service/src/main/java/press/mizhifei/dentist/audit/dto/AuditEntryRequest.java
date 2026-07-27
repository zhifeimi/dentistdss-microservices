package press.mizhifei.dentist.audit.dto;

import lombok.*;

import java.util.Map;

/**
 * Audit ingestion payload. There is deliberately no {@code actor} field: the
 * actor is always the verified service credential's subject, assigned
 * server-side. {@code assertedUserId}/{@code assertedClinicId} are
 * caller-claimed context about who the audit event concerns — useful for
 * queries, but NOT independently verified by this service and never trusted
 * for authorization.
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
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