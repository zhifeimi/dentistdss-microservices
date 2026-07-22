package press.mizhifei.dentist.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.auth.dto.ApiResponse;
import press.mizhifei.dentist.auth.dto.ApprovalRequestResponse;
import press.mizhifei.dentist.auth.dto.ReviewApprovalRequest;
import press.mizhifei.dentist.auth.model.AuthProvider;
import press.mizhifei.dentist.auth.model.Clinic;
import press.mizhifei.dentist.auth.model.Role;
import press.mizhifei.dentist.auth.model.User;
import press.mizhifei.dentist.auth.model.UserApprovalRequest;
import press.mizhifei.dentist.auth.repository.ClinicRepository;
import press.mizhifei.dentist.auth.repository.UserApprovalRequestRepository;
import press.mizhifei.dentist.auth.repository.UserRepository;
import press.mizhifei.dentist.auth.client.NotificationServiceClient;
import press.mizhifei.dentist.auth.dto.NotificationEmailRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserApprovalService {

    private final UserApprovalRequestRepository approvalRequestRepository;
    private final UserRepository userRepository;
    private final ClinicRepository clinicRepository;
    private final NotificationServiceClient notificationServiceClient;
    private final AuthSessionService authSessionService;

    @Transactional(readOnly = true)
    public boolean hasMatchingPendingApprovalRequest(
            Long userId,
            Role requestedRole,
            Long clinicId) {
        return hasMatchingApprovalRequest(
                userId, requestedRole, clinicId, User.ApprovalStatus.PENDING);
    }

    private boolean hasMatchingApprovalRequest(
            Long userId,
            Role requestedRole,
            Long clinicId,
            User.ApprovalStatus status) {
        return approvalRequestRepository.findByUserIdAndStatus(userId, status.toString())
                .filter(request -> request.getRequestedRole() == requestedRole)
                .filter(request -> Objects.equals(request.getClinicId(), clinicId))
                .isPresent();
    }

    @Transactional
    public ApiResponse<ApprovalRequestResponse> createApprovalRequest(Long userId, String requestReason) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Role requestedRole = determineRequestedRole(user);

        Optional<UserApprovalRequest> existingRequest = approvalRequestRepository
                .findByUserIdAndStatus(userId, User.ApprovalStatus.PENDING.toString());
        if (existingRequest.isPresent()) {
            UserApprovalRequest request = existingRequest.get();
            if (request.getRequestedRole() != requestedRole
                    || !Objects.equals(request.getClinicId(), user.getClinicId())) {
                return ApiResponse.error("Unable to create approval request");
            }
            return ApiResponse.success(toResponse(request));
        }

        UserApprovalRequest approvalRequest = UserApprovalRequest.builder()
                .userId(userId)
                .requestedRole(requestedRole)
                .clinicId(user.getClinicId())
                .status(User.ApprovalStatus.PENDING)
                .requestReason(requestReason)
                .build();

        UserApprovalRequest saved = approvalRequestRepository.save(approvalRequest);
        sendApprovalNotification(user, requestedRole);

        log.info("Created approval request {} for user {} requesting role {}",
                saved.getId(), userId, requestedRole);

        return ApiResponse.success(toResponse(saved));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ApiResponse<ApprovalRequestResponse> reviewApprovalRequest(Integer requestId,
            ReviewApprovalRequest reviewRequest,
            Long reviewedBy) {
        UserApprovalRequest approvalRequest = approvalRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Approval request not found"));

        if (approvalRequest.getStatus() != User.ApprovalStatus.PENDING
                || approvalRequest.getRequestedRole() == Role.PATIENT) {
            return ApiResponse.error("No pending approval request found");
        }

        User user = userRepository.findById(approvalRequest.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        User reviewer = userRepository.findById(reviewedBy)
                .orElseThrow(() -> new IllegalArgumentException("Reviewer not found"));
        if (Objects.equals(user.getId(), reviewer.getId())
                || !isAuthorizedReviewer(reviewer, approvalRequest)
                || !isPendingApprovalApplicant(user, approvalRequest)) {
            return ApiResponse.error("Unable to review approval request");
        }

        Clinic clinicToApprove = null;
        if (approvalRequest.getRequestedRole() == Role.CLINIC_ADMIN) {
            clinicToApprove = findPendingOwnedClinic(user, approvalRequest);
            if (clinicToApprove == null) {
                return ApiResponse.error("Unable to review approval request");
            }
        } else if (!isActiveStaffClinic(approvalRequest, reviewer)) {
            return ApiResponse.error("Unable to review approval request");
        }

        LocalDateTime reviewedAt = LocalDateTime.now();
        User.ApprovalStatus newStatus = reviewRequest.getApproved()
                ? User.ApprovalStatus.APPROVED
                : User.ApprovalStatus.REJECTED;
        approvalRequest.setStatus(newStatus);
        approvalRequest.setReviewNotes(reviewRequest.getReviewNotes());
        approvalRequest.setReviewedBy(reviewedBy);
        approvalRequest.setReviewedAt(reviewedAt);
        if (!reviewRequest.getApproved() && clinicToApprove != null) {
            // Release the clinic foreign key before deleting the rejected pending clinic.
            approvalRequest.setClinicId(null);
        }
        UserApprovalRequest updatedRequest = approvalRequestRepository.save(approvalRequest);

        if (reviewRequest.getApproved()) {
            user.setApprovalStatus(User.ApprovalStatus.APPROVED);
            user.setApprovedBy(reviewedBy.toString());
            user.setApprovalDate(reviewedAt);
            user.setApprovalRejectionReason(null);
            user.setEnabled(true);
        } else {
            user.setApprovalStatus(User.ApprovalStatus.REJECTED);
            user.setApprovedBy(null);
            user.setApprovalDate(null);
            user.setApprovalRejectionReason(reviewRequest.getReviewNotes());
            user.setEnabled(false);
            if (clinicToApprove != null) {
                user.setClinicId(null);
                user.setClinicName(null);
            }
        }
        authSessionService.publishSecurityChangeAndRevokeAll(user);
        userRepository.save(user);

        if (clinicToApprove != null) {
            if (reviewRequest.getApproved()) {
                clinicToApprove.setApprovalBy(reviewedBy);
                clinicToApprove.setApprovalDate(reviewedAt);
                clinicToApprove.setApproved(true);
                clinicToApprove.setEnabled(true);
                clinicRepository.save(clinicToApprove);
            } else {
                clinicRepository.delete(clinicToApprove);
            }
        }

        sendApprovalResultNotification(
                user, reviewRequest.getApproved(), reviewRequest.getReviewNotes());

        log.info("Reviewed approval request {} for user {} - {}",
                requestId, approvalRequest.getUserId(),
                reviewRequest.getApproved() ? "APPROVED" : "REJECTED");

        return ApiResponse.success(toResponse(updatedRequest));
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequestResponse> getPendingApprovalRequestsForReviewer(
            Long reviewerId) {
        User reviewer = requireActiveReviewer(reviewerId);
        if (hasOnlyRole(reviewer, Role.SYSTEM_ADMIN)) {
            return getPendingApprovalRequests();
        }
        if (hasOnlyRole(reviewer, Role.CLINIC_ADMIN)
                && reviewer.getClinicId() != null
                && reviewer.getApprovalStatus() == User.ApprovalStatus.APPROVED
                && isActiveStaffClinicReviewer(reviewer)) {
            return approvalRequestRepository
                    .findByClinicIdAndStatusOrderByCreatedAtDesc(
                            reviewer.getClinicId(),
                            User.ApprovalStatus.PENDING.toString())
                    .stream()
                    .filter(request -> request.getRequestedRole() == Role.DENTIST
                            || request.getRequestedRole() == Role.RECEPTIONIST)
                    .map(this::toResponse)
                    .collect(Collectors.toList());
        }
        throw new AccessDeniedException("Approval access denied");
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequestResponse> getClinicPendingApprovals(
            Long clinicId,
            Long reviewerId) {
        User reviewer = requireActiveReviewer(reviewerId);
        if (hasOnlyRole(reviewer, Role.SYSTEM_ADMIN)) {
            return getClinicPendingApprovals(clinicId);
        }
        if (!hasOnlyRole(reviewer, Role.CLINIC_ADMIN)
                || !Objects.equals(reviewer.getClinicId(), clinicId)
                || reviewer.getApprovalStatus() != User.ApprovalStatus.APPROVED
                || !isActiveStaffClinicReviewer(reviewer)) {
            throw new AccessDeniedException("Approval access denied");
        }
        return approvalRequestRepository
                .findByClinicIdAndStatusOrderByCreatedAtDesc(
                        clinicId, User.ApprovalStatus.PENDING.toString())
                .stream()
                .filter(request -> request.getRequestedRole() == Role.DENTIST
                        || request.getRequestedRole() == Role.RECEPTIONIST)
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequestResponse> getPendingApprovalRequests() {
        List<UserApprovalRequest> pendingRequests = approvalRequestRepository
                .findByStatus(User.ApprovalStatus.PENDING.toString());

        return pendingRequests.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequestResponse> getPendingApprovalRequests(String userEmail) {
        // Get user information to determine clinic filtering
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found with email: " + userEmail));

        List<UserApprovalRequest> pendingRequests;

        // Filter by clinic if user has clinic_id and is CLINIC_ADMIN
        if (user.getClinicId() != null && user.getRoles().contains(Role.CLINIC_ADMIN)) {
            pendingRequests = approvalRequestRepository
                    .findByClinicIdAndStatusOrderByCreatedAtDesc(user.getClinicId(), User.ApprovalStatus.PENDING.toString());
        } else {
            // If no clinic_id, return all pending requests (for SYSTEM_ADMIN)
            pendingRequests = approvalRequestRepository
                    .findByStatus(User.ApprovalStatus.PENDING.toString());
        }

        return pendingRequests.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequestResponse> getClinicPendingApprovals(Long clinicId) {
        List<UserApprovalRequest> pendingRequests = approvalRequestRepository
                .findByClinicIdAndStatusOrderByCreatedAtDesc(clinicId, User.ApprovalStatus.PENDING.toString());

        return pendingRequests.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequestResponse> getUserApprovalHistory(Long userId) {
        List<UserApprovalRequest> requests = approvalRequestRepository
                .findByUserIdOrderByCreatedAtDesc(userId);

        return requests.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequestResponse> getReviewedByUser(Long reviewerId) {
        List<UserApprovalRequest> requests = approvalRequestRepository
                .findByReviewedByOrderByReviewedAtDesc(reviewerId);

        return requests.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private boolean isPendingApprovalApplicant(
            User user,
            UserApprovalRequest approvalRequest) {
        return user.getId() != null
                && Objects.equals(user.getId(), approvalRequest.getUserId())
                && !user.isEnabled()
                && user.isEmailVerified()
                && user.getProvider() == AuthProvider.LOCAL
                && user.getProviderId() == null
                && user.getPassword() != null
                && !user.getPassword().isBlank()
                && hasOnlyRole(user, approvalRequest.getRequestedRole())
                && Objects.equals(user.getClinicId(), approvalRequest.getClinicId())
                && user.getApprovalStatus() == User.ApprovalStatus.PENDING
                && user.getApprovedBy() == null
                && user.getApprovalDate() == null
                && user.getApprovalRejectionReason() == null
                && user.isAccountNonExpired()
                && user.isCredentialsNonExpired()
                && user.isAccountNonLocked();
    }

    private boolean isAuthorizedReviewer(
            User reviewer,
            UserApprovalRequest approvalRequest) {
        if (!isActiveReviewerAccount(reviewer)) {
            return false;
        }
        if (approvalRequest.getRequestedRole() == Role.CLINIC_ADMIN) {
            return hasOnlyRole(reviewer, Role.SYSTEM_ADMIN);
        }
        if (approvalRequest.getRequestedRole() == Role.DENTIST
                || approvalRequest.getRequestedRole() == Role.RECEPTIONIST) {
            return hasOnlyRole(reviewer, Role.CLINIC_ADMIN)
                    && reviewer.getApprovalStatus() == User.ApprovalStatus.APPROVED
                    && reviewer.getApprovedBy() != null
                    && reviewer.getApprovalDate() != null
                    && Objects.equals(
                            reviewer.getClinicId(), approvalRequest.getClinicId());
        }
        return false;
    }

    private User requireActiveReviewer(Long reviewerId) {
        User reviewer = userRepository.findById(reviewerId)
                .orElseThrow(() -> new AccessDeniedException("Approval access denied"));
        if (!isActiveReviewerAccount(reviewer)) {
            throw new AccessDeniedException("Approval access denied");
        }
        return reviewer;
    }

    private boolean isActiveReviewerAccount(User reviewer) {
        return reviewer.getId() != null
                && reviewer.isEnabled()
                && reviewer.isEmailVerified()
                && reviewer.getRoles() != null
                && reviewer.isAccountNonExpired()
                && reviewer.isCredentialsNonExpired()
                && reviewer.isAccountNonLocked();
    }

    private boolean isActiveStaffClinicReviewer(User reviewer) {
        return reviewer.getClinicId() != null
                && clinicRepository.findById(reviewer.getClinicId())
                        .filter(clinic -> Boolean.TRUE.equals(clinic.getEnabled()))
                        .filter(clinic -> Boolean.TRUE.equals(clinic.getApproved()))
                        .filter(clinic -> clinic.getAdmin() != null)
                        .filter(clinic -> Objects.equals(
                                clinic.getAdmin().getId(), reviewer.getId()))
                        .isPresent();
    }

    private Clinic findPendingOwnedClinic(
            User user,
            UserApprovalRequest approvalRequest) {
        if (approvalRequest.getClinicId() == null) {
            return null;
        }
        return clinicRepository.findById(approvalRequest.getClinicId())
                .filter(clinic -> Boolean.FALSE.equals(clinic.getEnabled()))
                .filter(clinic -> Boolean.FALSE.equals(clinic.getApproved()))
                .filter(clinic -> clinic.getApprovalBy() == null)
                .filter(clinic -> clinic.getApprovalDate() == null)
                .filter(clinic -> clinic.getAdmin() != null)
                .filter(clinic -> Objects.equals(
                        clinic.getAdmin().getId(), user.getId()))
                .orElse(null);
    }

    private boolean isActiveStaffClinic(
            UserApprovalRequest approvalRequest,
            User reviewer) {
        if (approvalRequest.getClinicId() == null) {
            return false;
        }
        return clinicRepository.findById(approvalRequest.getClinicId())
                .filter(clinic -> Boolean.TRUE.equals(clinic.getEnabled()))
                .filter(clinic -> Boolean.TRUE.equals(clinic.getApproved()))
                .filter(clinic -> clinic.getAdmin() != null)
                .filter(clinic -> Objects.equals(
                        clinic.getAdmin().getId(), reviewer.getId()))
                .filter(clinic -> Objects.equals(
                        clinic.getAdmin().getClinicId(), clinic.getId()))
                .filter(clinic -> clinic.getAdmin().isEnabled())
                .filter(clinic -> clinic.getAdmin().isEmailVerified())
                .filter(clinic -> clinic.getAdmin().getApprovalStatus()
                        == User.ApprovalStatus.APPROVED)
                .isPresent();
    }

    private boolean hasOnlyRole(User user, Role role) {
        return user.getRoles() != null
                && user.getRoles().size() == 1
                && user.getRoles().contains(role);
    }

    private Role determineRequestedRole(User user) {
        if (user.getRoles().contains(Role.DENTIST)) {
            return Role.DENTIST;
        } else if (user.getRoles().contains(Role.RECEPTIONIST)) {
            return Role.RECEPTIONIST;
        } else if (user.getRoles().contains(Role.CLINIC_ADMIN)) {
            return Role.CLINIC_ADMIN;
        }
        return Role.PATIENT;
    }

    private void sendApprovalNotification(User user, Role requestedRole) {
        // Determine who should be notified
        List<User> approvers;

        if (requestedRole == Role.CLINIC_ADMIN || user.getClinicId() == null) {
            // System admin approves clinic admins or users without clinic
            approvers = userRepository.findByRoles(Role.SYSTEM_ADMIN);
        } else {
            // Clinic admin approves staff within their clinic
            approvers = userRepository.findByClinicIdAndRoles(user.getClinicId(), Role.CLINIC_ADMIN);
        }

        Map<String, String> templateVariables = new HashMap<>();
        templateVariables.put("user_name", user.getFirstName() + " " + user.getLastName());
        templateVariables.put("role", requestedRole.toString());

        approvers.forEach(approver -> {
            notificationServiceClient.sendNotificationEmail(
                    new NotificationEmailRequest(approver.getEmail(), "user_approval_request", templateVariables));
        });
    }

    private void sendApprovalResultNotification(User user, boolean approved, String reviewNotes) {
        Map<String, String> templateVariables = new HashMap<>();
        templateVariables.put("user_name", user.getFirstName() + " " + user.getLastName());
        templateVariables.put("role", user.getRoles().iterator().next().toString());
        templateVariables.put("status", approved ? "approved" : "rejected");
        templateVariables.put("reason", reviewNotes != null ? reviewNotes : "");

        notificationServiceClient.sendNotificationEmail(
                new NotificationEmailRequest(user.getEmail(), "user_approval_result", templateVariables));
    }

    private ApprovalRequestResponse toResponse(UserApprovalRequest request) {
        User user = userRepository.findById(request.getUserId()).orElse(null);

        ApprovalRequestResponse response = ApprovalRequestResponse.builder()
                .id(request.getId())
                .userId(request.getUserId())
                .userName(user != null ? user.getFirstName() + " " + user.getLastName() : "Unknown")
                .userEmail(user != null ? user.getEmail() : "Unknown")
                .requestedRole(request.getRequestedRole().toString())
                .clinicId(request.getClinicId())
                .status(request.getStatus().toString())
                .requestReason(request.getRequestReason())
                .supportingDocuments(request.getSupportingDocuments())
                .reviewedBy(request.getReviewedBy())
                .reviewNotes(request.getReviewNotes())
                .reviewedAt(request.getReviewedAt())
                .createdAt(request.getCreatedAt())
                .build();

        // Add clinic name if applicable
        if (request.getClinicId() != null) {
            clinicRepository.findById(request.getClinicId())
                    .ifPresent(clinic -> response.setClinicName(clinic.getName()));
        }

        // Add reviewer name if applicable
        if (request.getReviewedBy() != null) {
            userRepository.findById(request.getReviewedBy()).ifPresent(
                    reviewer -> response.setReviewerName(reviewer.getFirstName() + " " + reviewer.getLastName()));
        }

        return response;
    }
}