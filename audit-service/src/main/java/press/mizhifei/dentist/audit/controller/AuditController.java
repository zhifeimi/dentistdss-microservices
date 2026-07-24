package press.mizhifei.dentist.audit.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import press.mizhifei.dentist.audit.dto.ApiResponse;
import press.mizhifei.dentist.audit.dto.AuditEntryRequest;
import press.mizhifei.dentist.audit.dto.AuditEntryResponse;
import press.mizhifei.dentist.audit.service.AuditService;

import java.util.List;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    /**
     * Ingests an audit entry. Restricted to verified service callers holding
     * the {@code audit:ingest} scope; the recorded actor is the credential's
     * cryptographic subject (the calling service's registered name), never a
     * caller-supplied field.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('SERVICE_AUDIT_INGEST')")
    public Mono<ResponseEntity<ApiResponse<AuditEntryResponse>>> record(
            @RequestBody AuditEntryRequest request,
            @AuthenticationPrincipal Jwt caller) {
        return Mono.fromCallable(() ->
                        ResponseEntity.ok(ApiResponse.success(
                                auditService.record(request, caller.getSubject()))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public Mono<ResponseEntity<ApiResponse<List<AuditEntryResponse>>>> listAll() {
        return Mono.fromCallable(() ->
                        ResponseEntity.ok(ApiResponse.success(auditService.listAll())))
                .subscribeOn(Schedulers.boundedElastic());
    }
} 