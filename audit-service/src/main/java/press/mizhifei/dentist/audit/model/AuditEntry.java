package press.mizhifei.dentist.audit.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "audit_entries")
public class AuditEntry {
    @Id
    private String id;

    /** Verified service caller the credential subject identifies; server-assigned. */
    private String actor;
    private String action;      // e.g. CREATE_PATIENT
    private String target;      // resource id or description

    /** Caller-claimed context: who the event concerns (not independently verified). */
    private Long assertedUserId;
    private Long assertedClinicId;

    private LocalDateTime timestamp;

    private Map<String, Object> context; // additional metadata
} 