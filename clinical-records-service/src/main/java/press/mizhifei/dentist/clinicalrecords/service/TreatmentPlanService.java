package press.mizhifei.dentist.clinicalrecords.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.clinicalrecords.client.AuthServiceClient;
import press.mizhifei.dentist.clinicalrecords.client.ClinicServiceClient;
import press.mizhifei.dentist.clinicalrecords.client.NotificationClient;
import press.mizhifei.dentist.clinicalrecords.dto.TreatmentPlanRequest;
import press.mizhifei.dentist.clinicalrecords.dto.TreatmentPlanResponse;
import press.mizhifei.dentist.clinicalrecords.exception.ClinicalResourceNotFoundException;
import press.mizhifei.dentist.clinicalrecords.exception.ClinicalStateConflictException;
import press.mizhifei.dentist.clinicalrecords.exception.InvalidClinicalRequestException;
import press.mizhifei.dentist.clinicalrecords.model.TreatmentPlan;
import press.mizhifei.dentist.clinicalrecords.model.TreatmentPlanItem;
import press.mizhifei.dentist.clinicalrecords.repository.TreatmentPlanItemRepository;
import press.mizhifei.dentist.clinicalrecords.repository.TreatmentPlanRepository;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsAccess;
import press.mizhifei.dentist.clinicalrecords.security.ClinicalRecordsActor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TreatmentPlanService {

    private static final Set<String> ITEM_STATUSES = Set.of("PENDING", "SCHEDULED", "COMPLETED");

    private final TreatmentPlanRepository treatmentPlanRepository;
    private final TreatmentPlanItemRepository treatmentPlanItemRepository;
    private final AuthServiceClient authServiceClient;
    private final ClinicServiceClient clinicServiceClient;
    private final NotificationClient notificationClient;

    @Transactional
    public TreatmentPlanResponse createTreatmentPlan(
            ClinicalRecordsActor actor,
            TreatmentPlanRequest request) {
        ClinicalRecordsAccess.WriteOwner owner = ClinicalRecordsAccess.resolveWriteOwner(
                actor,
                request.getPatientId(),
                request.getDentistId(),
                request.getClinicId());

        Integer version = 1;
        if (request.getParentPlanId() != null) {
            int parentPlanId = requirePlanId(request.getParentPlanId());
            TreatmentPlan parent = findManageablePlan(actor, parentPlanId);
            requireMatchingOwner(parent, owner);
            List<TreatmentPlan> versions = actor.isSystemAdmin()
                    ? treatmentPlanRepository.findPlanVersions(parentPlanId)
                    : treatmentPlanRepository.findPlanVersionsByDentistIdAndClinicId(
                            parentPlanId,
                            actor.userId(),
                            actor.requiredClinicId());
            version = versions.isEmpty() ? 2 : versions.get(0).getVersion() + 1;
        }

        TreatmentPlan treatmentPlan = TreatmentPlan.builder()
                .patientId(owner.patientId())
                .dentistId(owner.dentistId())
                .clinicId(owner.clinicId())
                .planName(request.getPlanName())
                .description(request.getDescription())
                .totalCost(request.getTotalCost())
                .insuranceCoverage(request.getInsuranceCoverage())
                .patientCost(request.getPatientCost())
                .version(version)
                .parentPlanId(request.getParentPlanId())
                .build();

        TreatmentPlan saved = treatmentPlanRepository.save(treatmentPlan);
        savePlanItems(saved, request);
        log.info("Created treatment plan {}", saved.getId());

        sendNotificationSafely(saved, "CREATED");
        return toResponse(saved);
    }

    @Transactional
    public TreatmentPlanResponse acceptTreatmentPlan(ClinicalRecordsActor actor, Integer planId) {
        ClinicalRecordsAccess.requirePatientAcceptance(actor);
        TreatmentPlan treatmentPlan = treatmentPlanRepository.findByIdAndPatientId(
                        requirePlanId(planId),
                        actor.userId())
                .orElseThrow(ClinicalResourceNotFoundException::new);
        if (!"PROPOSED".equals(treatmentPlan.getStatus())) {
            throw new ClinicalStateConflictException();
        }

        treatmentPlan.setStatus("ACCEPTED");
        treatmentPlan.setAcceptedAt(LocalDateTime.now());
        TreatmentPlan saved = treatmentPlanRepository.save(treatmentPlan);
        log.info("Accepted treatment plan {}", planId);
        sendNotificationSafely(saved, "ACCEPTED");
        return toResponse(saved);
    }

    @Transactional
    public TreatmentPlanResponse startTreatmentPlan(ClinicalRecordsActor actor, Integer planId) {
        TreatmentPlan treatmentPlan = findManageablePlan(actor, planId);
        if (!"ACCEPTED".equals(treatmentPlan.getStatus())) {
            throw new ClinicalStateConflictException();
        }

        treatmentPlan.setStatus("IN_PROGRESS");
        TreatmentPlan saved = treatmentPlanRepository.save(treatmentPlan);
        log.info("Started treatment plan {}", planId);
        return toResponse(saved);
    }

    @Transactional
    public TreatmentPlanResponse completeTreatmentPlan(ClinicalRecordsActor actor, Integer planId) {
        TreatmentPlan treatmentPlan = findManageablePlan(actor, planId);
        if (!"IN_PROGRESS".equals(treatmentPlan.getStatus())) {
            throw new ClinicalStateConflictException();
        }

        treatmentPlan.setStatus("COMPLETED");
        treatmentPlan.setCompletedAt(LocalDateTime.now());
        TreatmentPlan saved = treatmentPlanRepository.save(treatmentPlan);
        log.info("Completed treatment plan {}", planId);
        sendNotificationSafely(saved, "COMPLETED");
        return toResponse(saved);
    }

    @Transactional
    public TreatmentPlanResponse updateTreatmentPlanItemStatus(
            ClinicalRecordsActor actor,
            Integer planId,
            Integer itemId,
            String status) {
        int targetPlanId = requirePlanId(planId);
        TreatmentPlan plan = findManageablePlan(actor, targetPlanId);
        if (!"IN_PROGRESS".equals(plan.getStatus())) {
            throw new ClinicalStateConflictException();
        }
        if (itemId == null || itemId <= 0 || !ITEM_STATUSES.contains(status)) {
            throw new InvalidClinicalRequestException();
        }

        TreatmentPlanItem item = treatmentPlanItemRepository.findByIdAndTreatmentPlanId(itemId, targetPlanId)
                .orElseThrow(ClinicalResourceNotFoundException::new);
        item.setStatus(status);
        treatmentPlanItemRepository.save(item);
        log.info("Updated treatment plan item {} status", itemId);

        List<TreatmentPlanItem> allItems = treatmentPlanItemRepository
                .findByTreatmentPlanIdOrderBySequenceOrder(targetPlanId);
        boolean allCompleted = !allItems.isEmpty()
                && allItems.stream().allMatch(candidate -> "COMPLETED".equals(candidate.getStatus()));
        if (allCompleted && "IN_PROGRESS".equals(plan.getStatus())) {
            plan.setStatus("COMPLETED");
            plan.setCompletedAt(LocalDateTime.now());
            plan = treatmentPlanRepository.save(plan);
            log.info("Auto-completed treatment plan {}", targetPlanId);
        }

        return toResponse(plan);
    }

    @Transactional(readOnly = true)
    public TreatmentPlanResponse getTreatmentPlan(ClinicalRecordsActor actor, Integer planId) {
        return toResponse(findReadablePlan(actor, planId));
    }

    @Transactional(readOnly = true)
    public List<TreatmentPlanResponse> getPatientTreatmentPlans(
            ClinicalRecordsActor actor,
            Long patientId) {
        long targetPatientId = ClinicalRecordsAccess.requirePositive(patientId);
        List<TreatmentPlan> plans;
        if (actor.isSystemAdmin() || ClinicalRecordsAccess.matchesPatient(actor, targetPatientId)) {
            plans = treatmentPlanRepository.findByPatientIdOrderByCreatedAtDesc(targetPatientId);
        } else if (actor.isDentist()) {
            plans = treatmentPlanRepository.findByPatientIdAndDentistIdAndClinicIdOrderByCreatedAtDesc(
                    targetPatientId,
                    actor.userId(),
                    actor.requiredClinicId());
        } else {
            throw new AccessDeniedException("Treatment-plan read access is unavailable");
        }
        return plans.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<TreatmentPlanResponse> getDentistTreatmentPlans(
            ClinicalRecordsActor actor,
            Long dentistId) {
        long targetDentistId = ClinicalRecordsAccess.requirePositive(dentistId);
        List<TreatmentPlan> plans;
        if (actor.isSystemAdmin()) {
            plans = treatmentPlanRepository.findByDentistIdOrderByCreatedAtDesc(targetDentistId);
        } else if (actor.isDentist() && actor.userId() == targetDentistId) {
            plans = treatmentPlanRepository.findByDentistIdAndClinicIdOrderByCreatedAtDesc(
                    targetDentistId,
                    actor.requiredClinicId());
        } else if (actor.isDentist()) {
            throw new ClinicalResourceNotFoundException();
        } else {
            throw new AccessDeniedException("Treatment-plan read access is unavailable");
        }
        return plans.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<TreatmentPlanResponse> getPlanVersions(
            ClinicalRecordsActor actor,
            Integer parentPlanId) {
        int targetParentPlanId = requirePlanId(parentPlanId);
        TreatmentPlan parent = findReadablePlan(actor, targetParentPlanId);
        List<TreatmentPlan> plans;
        if (actor.isSystemAdmin()) {
            plans = treatmentPlanRepository.findPlanVersions(targetParentPlanId);
        } else if (ClinicalRecordsAccess.matchesPatient(actor, parent.getPatientId())) {
            plans = treatmentPlanRepository.findPlanVersionsByPatientId(targetParentPlanId, actor.userId());
        } else if (ClinicalRecordsAccess.matchesDentist(actor, parent.getDentistId(), parent.getClinicId())) {
            plans = treatmentPlanRepository.findPlanVersionsByDentistIdAndClinicId(
                    targetParentPlanId,
                    actor.userId(),
                    actor.requiredClinicId());
        } else {
            throw new ClinicalResourceNotFoundException();
        }
        return plans.stream().map(this::toResponse).toList();
    }

    private TreatmentPlan findReadablePlan(ClinicalRecordsActor actor, Integer planId) {
        int targetPlanId = requirePlanId(planId);
        Optional<TreatmentPlan> plan;
        if (actor.isSystemAdmin()) {
            plan = treatmentPlanRepository.findById(targetPlanId);
        } else {
            plan = Optional.empty();
            if (actor.isPatient()) {
                plan = treatmentPlanRepository.findByIdAndPatientId(targetPlanId, actor.userId());
            }
            if (plan.isEmpty() && actor.isDentist()) {
                plan = treatmentPlanRepository.findByIdAndDentistIdAndClinicId(
                        targetPlanId,
                        actor.userId(),
                        actor.requiredClinicId());
            }
            if (!actor.isPatient() && !actor.isDentist()) {
                throw new AccessDeniedException("Treatment-plan read access is unavailable");
            }
        }
        return plan.orElseThrow(ClinicalResourceNotFoundException::new);
    }

    private TreatmentPlan findManageablePlan(ClinicalRecordsActor actor, Integer planId) {
        int targetPlanId = requirePlanId(planId);
        ClinicalRecordsAccess.requireClinicalManager(actor);
        Optional<TreatmentPlan> plan = actor.isSystemAdmin()
                ? treatmentPlanRepository.findById(targetPlanId)
                : treatmentPlanRepository.findByIdAndDentistIdAndClinicId(
                        targetPlanId,
                        actor.userId(),
                        actor.requiredClinicId());
        return plan.orElseThrow(ClinicalResourceNotFoundException::new);
    }

    private void savePlanItems(TreatmentPlan plan, TreatmentPlanRequest request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            return;
        }

        int nextSequenceOrder = 1;
        for (TreatmentPlanRequest.TreatmentPlanItemRequest itemRequest : request.getItems()) {
            Integer sequenceOrder = itemRequest.getSequenceOrder();
            if (sequenceOrder == null || sequenceOrder <= 0) {
                sequenceOrder = nextSequenceOrder;
            }
            nextSequenceOrder = Math.max(nextSequenceOrder + 1, sequenceOrder + 1);
            treatmentPlanItemRepository.save(TreatmentPlanItem.builder()
                    .treatmentPlanId(plan.getId())
                    .serviceId(itemRequest.getServiceId())
                    .toothNumber(itemRequest.getToothNumber())
                    .description(itemRequest.getDescription())
                    .cost(itemRequest.getCost())
                    .sequenceOrder(sequenceOrder)
                    .notes(itemRequest.getNotes())
                    .status("PENDING")
                    .build());
        }
    }

    private void requireMatchingOwner(
            TreatmentPlan plan,
            ClinicalRecordsAccess.WriteOwner owner) {
        if (!Objects.equals(plan.getPatientId(), owner.patientId())
                || !Objects.equals(plan.getDentistId(), owner.dentistId())
                || !Objects.equals(plan.getClinicId(), owner.clinicId())) {
            throw new InvalidClinicalRequestException();
        }
    }

    private int requirePlanId(Integer planId) {
        if (planId == null || planId <= 0) {
            throw new InvalidClinicalRequestException();
        }
        return planId;
    }

    private void sendNotificationSafely(TreatmentPlan plan, String action) {
        try {
            Map<String, Object> notification = new HashMap<>();
            notification.put("userId", plan.getPatientId());
            notification.put("type", "IN_APP");
            notification.put("subject", "Treatment plan " + action.toLowerCase());
            notification.put("body", "Your treatment plan has been " + action.toLowerCase());
            notification.put("metadata", Map.of("treatment_plan_id", plan.getId()));
            notificationClient.sendNotification(notification);
        } catch (Exception exception) {
            log.warn("Treatment-plan notification delivery failed");
        }
    }

    private TreatmentPlanResponse toResponse(TreatmentPlan treatmentPlan) {
        List<TreatmentPlanResponse.TreatmentPlanItemResponse> itemResponses = treatmentPlanItemRepository
                .findByTreatmentPlanIdOrderBySequenceOrder(treatmentPlan.getId())
                .stream()
                .map(this::toItemResponse)
                .toList();

        TreatmentPlanResponse response = TreatmentPlanResponse.builder()
                .id(treatmentPlan.getId())
                .patientId(treatmentPlan.getPatientId())
                .dentistId(treatmentPlan.getDentistId())
                .clinicId(treatmentPlan.getClinicId())
                .planName(treatmentPlan.getPlanName())
                .description(treatmentPlan.getDescription())
                .totalCost(treatmentPlan.getTotalCost())
                .insuranceCoverage(treatmentPlan.getInsuranceCoverage())
                .patientCost(treatmentPlan.getPatientCost())
                .status(treatmentPlan.getStatus())
                .version(treatmentPlan.getVersion())
                .parentPlanId(treatmentPlan.getParentPlanId())
                .createdAt(treatmentPlan.getCreatedAt())
                .acceptedAt(treatmentPlan.getAcceptedAt())
                .completedAt(treatmentPlan.getCompletedAt())
                .items(itemResponses)
                .build();

        try {
            response.setPatientName(authServiceClient.getUserFullName(treatmentPlan.getPatientId()));
        } catch (Exception exception) {
            log.warn("Failed to enrich treatment plan {} from a dependent service", treatmentPlan.getId());
        }
        try {
            response.setDentistName(authServiceClient.getUserFullName(treatmentPlan.getDentistId()));
        } catch (Exception exception) {
            log.warn("Failed to enrich treatment plan {} from a dependent service", treatmentPlan.getId());
        }
        try {
            response.setClinicName(clinicServiceClient.getClinic(treatmentPlan.getClinicId()).getName());
        } catch (Exception exception) {
            log.warn("Failed to enrich treatment plan {} from a dependent service", treatmentPlan.getId());
        }
        return response;
    }

    private TreatmentPlanResponse.TreatmentPlanItemResponse toItemResponse(TreatmentPlanItem item) {
        TreatmentPlanResponse.TreatmentPlanItemResponse response = TreatmentPlanResponse.TreatmentPlanItemResponse.builder()
                .id(item.getId())
                .serviceId(item.getServiceId())
                .toothNumber(item.getToothNumber())
                .description(item.getDescription())
                .cost(item.getCost())
                .status(item.getStatus())
                .sequenceOrder(item.getSequenceOrder())
                .notes(item.getNotes())
                .build();

        if (item.getServiceId() != null) {
            try {
                response.setServiceName(clinicServiceClient.getService(item.getServiceId()).getName());
            } catch (Exception exception) {
                log.warn("Failed to enrich treatment-plan item {} from a dependent service", item.getId());
            }
        }
        return response;
    }
}
