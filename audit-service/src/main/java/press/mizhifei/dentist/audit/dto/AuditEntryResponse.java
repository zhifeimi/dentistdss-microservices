package press.mizhifei.dentist.audit.dto;

import lombok.*;

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
@AllArgsConstructor
@NoArgsConstructor
public class AuditEntryResponse {
    private String id;
    private String actor;
    private String action;
    private String target;
    private Long assertedUserId;
    private Long assertedClinicId;
    private LocalDateTime timestamp;
    private Map<String, Object> context;
} 