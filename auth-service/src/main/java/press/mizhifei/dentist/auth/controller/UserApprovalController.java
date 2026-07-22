package press.mizhifei.dentist.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import press.mizhifei.dentist.auth.dto.ApiResponse;
import press.mizhifei.dentist.auth.dto.ApprovalRequestResponse;
import press.mizhifei.dentist.auth.dto.ReviewApprovalRequest;
import press.mizhifei.dentist.auth.security.UserPrincipal;
import press.mizhifei.dentist.auth.service.UserApprovalService;

import java.util.List;

@RestController
@RequestMapping("/auth/approval")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN', 'CLINIC_ADMIN')")
public class UserApprovalController {

    private final UserApprovalService userApprovalService;

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<ApprovalRequestResponse>>> getPendingApprovalRequests(
            @AuthenticationPrincipal UserPrincipal reviewer) {
        return ResponseEntity.ok(ApiResponse.success(
                userApprovalService.getPendingApprovalRequestsForReviewer(
                        reviewer.getId())));
    }

    @GetMapping("/clinic/{clinicId}/pending")
    public ResponseEntity<ApiResponse<List<ApprovalRequestResponse>>> getClinicPendingApprovals(
            @PathVariable Long clinicId,
            @AuthenticationPrincipal UserPrincipal reviewer) {
        return ResponseEntity.ok(ApiResponse.success(
                userApprovalService.getClinicPendingApprovals(
                        clinicId, reviewer.getId())));
    }

    @PostMapping("/{requestId}/review")
    public ResponseEntity<ApiResponse<ApprovalRequestResponse>> reviewApprovalRequest(
            @PathVariable Integer requestId,
            @Valid @RequestBody ReviewApprovalRequest reviewRequest,
            @AuthenticationPrincipal UserPrincipal reviewer) {
        return ResponseEntity.ok(userApprovalService.reviewApprovalRequest(
                requestId, reviewRequest, reviewer.getId()));
    }
}
