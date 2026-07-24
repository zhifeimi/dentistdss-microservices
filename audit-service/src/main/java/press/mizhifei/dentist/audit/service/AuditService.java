package press.mizhifei.dentist.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.audit.dto.AuditEntryRequest;
import press.mizhifei.dentist.audit.dto.AuditEntryResponse;
import press.mizhifei.dentist.audit.model.AuditEntry;
import press.mizhifei.dentist.audit.repository.AuditEntryRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEntryRepository repository;

    /**
     * Records an audit entry. {@code actor} is the verified service credential
     * subject supplied by the controller — never a value from the request body.
     */
    @Transactional
    public AuditEntryResponse record(AuditEntryRequest request, String actor) {
        AuditEntry entry = AuditEntry.builder()
                .actor(actor)
                .action(request.getAction())
                .target(request.getTarget())
                .assertedUserId(request.getAssertedUserId())
                .assertedClinicId(request.getAssertedClinicId())
                .timestamp(LocalDateTime.now())
                .context(request.getContext())
                .build();
        AuditEntry saved = repository.save(entry);
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<AuditEntryResponse> listAll() {
        return repository.findAll().stream().map(this::toDto).collect(Collectors.toList());
    }

    private AuditEntryResponse toDto(AuditEntry entry) {
        return AuditEntryResponse.builder()
                .id(entry.getId())
                .actor(entry.getActor())
                .action(entry.getAction())
                .target(entry.getTarget())
                .assertedUserId(entry.getAssertedUserId())
                .assertedClinicId(entry.getAssertedClinicId())
                .timestamp(entry.getTimestamp())
                .context(entry.getContext())
                .build();
    }
} 