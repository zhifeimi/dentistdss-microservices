package press.mizhifei.dentist.auth.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import press.mizhifei.dentist.auth.dto.ApiResponse;
import press.mizhifei.dentist.auth.dto.ApprovalRequestResponse;
import press.mizhifei.dentist.auth.dto.ReviewApprovalRequest;
import press.mizhifei.dentist.auth.security.UserPrincipal;
import press.mizhifei.dentist.auth.service.UserApprovalService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserApprovalControllerTest {

    private UserApprovalService userApprovalService;
    private UserApprovalController controller;
    private UserPrincipal reviewer;

    @BeforeEach
    void setUp() {
        userApprovalService = mock(UserApprovalService.class);
        controller = new UserApprovalController(userApprovalService);
        reviewer = UserPrincipal.builder().id(100L).build();
    }

    @Test
    void approvalEndpointsRequireAdministrativeAuthority() {
        PreAuthorize authorization = UserApprovalController.class
                .getAnnotation(PreAuthorize.class);

        assertEquals(
                "hasAnyAuthority('SYSTEM_ADMIN', 'CLINIC_ADMIN')",
                authorization.value());
    }

    @Test
    void pendingRequestsAreScopedByAuthenticatedReviewer() {
        when(userApprovalService.getPendingApprovalRequestsForReviewer(100L))
                .thenReturn(List.of());

        ApiResponse<List<ApprovalRequestResponse>> response = controller
                .getPendingApprovalRequests(reviewer)
                .getBody();

        assertTrue(response != null && response.isSuccess());
        verify(userApprovalService).getPendingApprovalRequestsForReviewer(100L);
    }

    @Test
    void reviewUsesAuthenticatedReviewerIdentity() {
        ReviewApprovalRequest request = new ReviewApprovalRequest(true, "approved");
        when(userApprovalService.reviewApprovalRequest(7, request, 100L))
                .thenReturn(ApiResponse.success(
                        ApprovalRequestResponse.builder().id(7).build()));

        controller.reviewApprovalRequest(7, request, reviewer);

        verify(userApprovalService).reviewApprovalRequest(7, request, 100L);
    }
}
