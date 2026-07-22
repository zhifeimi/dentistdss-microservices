package press.mizhifei.dentist.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import press.mizhifei.dentist.auth.client.NotificationServiceClient;
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

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserApprovalServiceSecurityTest {

    private UserApprovalRequestRepository approvalRequestRepository;
    private UserRepository userRepository;
    private ClinicRepository clinicRepository;
    private NotificationServiceClient notificationServiceClient;
    private AuthSessionService authSessionService;
    private UserApprovalService userApprovalService;

    @BeforeEach
    void setUp() {
        approvalRequestRepository = mock(UserApprovalRequestRepository.class);
        userRepository = mock(UserRepository.class);
        clinicRepository = mock(ClinicRepository.class);
        notificationServiceClient = mock(NotificationServiceClient.class);
        authSessionService = mock(AuthSessionService.class);
        userApprovalService = new UserApprovalService(
                approvalRequestRepository,
                userRepository,
                clinicRepository,
                notificationServiceClient,
                authSessionService);
        when(notificationServiceClient.sendNotificationEmail(any()))
                .thenReturn(ResponseEntity.ok("sent"));
        when(approvalRequestRepository.save(any(UserApprovalRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createApprovalRequestReusesExactPendingRequest() {
        User user = pendingUser(Role.DENTIST, false);
        UserApprovalRequest pendingRequest = pendingRequest(Role.DENTIST, 9L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(approvalRequestRepository.findByUserIdAndStatus(
                42L, User.ApprovalStatus.PENDING.toString()))
                .thenReturn(Optional.of(pendingRequest));

        ApiResponse<ApprovalRequestResponse> response = userApprovalService
                .createApprovalRequest(42L, "duplicate retry");

        assertTrue(response.isSuccess());
        verify(approvalRequestRepository, never()).save(any());
        verify(notificationServiceClient, never()).sendNotificationEmail(any());
    }

    @Test
    void createApprovalRequestRejectsMismatchedPendingRequest() {
        User user = pendingUser(Role.DENTIST, false);
        UserApprovalRequest pendingRequest = pendingRequest(Role.RECEPTIONIST, 10L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(approvalRequestRepository.findByUserIdAndStatus(
                42L, User.ApprovalStatus.PENDING.toString()))
                .thenReturn(Optional.of(pendingRequest));

        ApiResponse<ApprovalRequestResponse> response = userApprovalService
                .createApprovalRequest(42L, "mismatched retry");

        assertFalse(response.isSuccess());
        verify(approvalRequestRepository, never()).save(any());
        verify(notificationServiceClient, never()).sendNotificationEmail(any());
    }

    @Test
    void approvalRejectsUnverifiedStaffBeforeMutation() {
        User user = pendingUser(Role.DENTIST, false);
        UserApprovalRequest request = pendingRequest(Role.DENTIST, 9L);
        stubStaffReview(user, request);

        ApiResponse<ApprovalRequestResponse> response = userApprovalService
                .reviewApprovalRequest(7, new ReviewApprovalRequest(true, "approved"), 100L);

        assertFalse(response.isSuccess());
        assertEquals(User.ApprovalStatus.PENDING, request.getStatus());
        assertEquals(User.ApprovalStatus.PENDING, user.getApprovalStatus());
        assertFalse(user.isEnabled());
        verify(approvalRequestRepository, never()).save(any(UserApprovalRequest.class));
        verify(userRepository, never()).save(any());
    }

    @Test
    void approvalEnablesAlreadyVerifiedStaff() {
        User user = pendingUser(Role.DENTIST, true);
        UserApprovalRequest request = pendingRequest(Role.DENTIST, 9L);
        stubStaffReview(user, request);

        ApiResponse<ApprovalRequestResponse> response = userApprovalService
                .reviewApprovalRequest(7, new ReviewApprovalRequest(true, "approved"), 100L);

        assertTrue(response.isSuccess());
        assertTrue(user.isEnabled());
        verify(authSessionService).publishSecurityChangeAndRevokeAll(user);
    }

    @Test
    void approvalRejectsApplicantWhoseRoleOrClinicChanged() {
        User user = pendingUser(Role.RECEPTIONIST, true);
        user.setClinicId(10L);
        UserApprovalRequest request = pendingRequest(Role.DENTIST, 9L);
        stubStaffReview(user, request);

        ApiResponse<ApprovalRequestResponse> response = userApprovalService
                .reviewApprovalRequest(7, new ReviewApprovalRequest(true, "approved"), 100L);

        assertFalse(response.isSuccess());
        assertFalse(user.isEnabled());
        verify(approvalRequestRepository, never()).save(any(UserApprovalRequest.class));
        verify(userRepository, never()).save(any());
    }

    @Test
    void approvalRejectsStaffReviewBySystemAdministrator() {
        User user = pendingUser(Role.DENTIST, true);
        UserApprovalRequest request = pendingRequest(Role.DENTIST, 9L);
        when(approvalRequestRepository.findById(7)).thenReturn(Optional.of(request));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(userRepository.findById(100L)).thenReturn(Optional.of(activeSystemAdminReviewer()));

        ApiResponse<ApprovalRequestResponse> response = userApprovalService
                .reviewApprovalRequest(7, new ReviewApprovalRequest(true, "approved"), 100L);

        assertFalse(response.isSuccess());
        assertFalse(user.isEnabled());
        verify(approvalRequestRepository, never()).save(any(UserApprovalRequest.class));
        verify(userRepository, never()).save(any());
    }

    @Test
    void approvalRejectsStaffReviewFromAnotherClinic() {
        User user = pendingUser(Role.DENTIST, true);
        UserApprovalRequest request = pendingRequest(Role.DENTIST, 9L);
        User reviewer = activeClinicAdminReviewer();
        reviewer.setClinicId(10L);
        when(approvalRequestRepository.findById(7)).thenReturn(Optional.of(request));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(userRepository.findById(100L)).thenReturn(Optional.of(reviewer));

        ApiResponse<ApprovalRequestResponse> response = userApprovalService
                .reviewApprovalRequest(7, new ReviewApprovalRequest(true, "approved"), 100L);

        assertFalse(response.isSuccess());
        assertFalse(user.isEnabled());
        assertEquals(User.ApprovalStatus.PENDING, request.getStatus());
        verify(approvalRequestRepository, never()).save(any(UserApprovalRequest.class));
        verify(userRepository, never()).save(any());
    }

    @Test
    void approvalRejectsStaffWhenClinicIsNoLongerActive() {
        User user = pendingUser(Role.DENTIST, true);
        UserApprovalRequest request = pendingRequest(Role.DENTIST, 9L);
        User reviewer = activeClinicAdminReviewer();
        Clinic disabledClinic = activeClinic(reviewer);
        disabledClinic.setEnabled(false);
        when(approvalRequestRepository.findById(7)).thenReturn(Optional.of(request));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(userRepository.findById(100L)).thenReturn(Optional.of(reviewer));
        when(clinicRepository.findById(9L)).thenReturn(Optional.of(disabledClinic));

        ApiResponse<ApprovalRequestResponse> response = userApprovalService
                .reviewApprovalRequest(7, new ReviewApprovalRequest(true, "approved"), 100L);

        assertFalse(response.isSuccess());
        assertFalse(user.isEnabled());
        assertEquals(User.ApprovalStatus.PENDING, request.getStatus());
        verify(approvalRequestRepository, never()).save(any(UserApprovalRequest.class));
        verify(userRepository, never()).save(any());
    }

    @Test
    void clinicApprovalRejectsUnverifiedAdministratorBeforeMutation() {
        User user = pendingUser(Role.CLINIC_ADMIN, false);
        UserApprovalRequest request = pendingRequest(Role.CLINIC_ADMIN, 9L);
        Clinic clinic = pendingClinic(user);
        stubClinicAdminReview(user, request, clinic);

        ApiResponse<ApprovalRequestResponse> response = userApprovalService
                .reviewApprovalRequest(7, new ReviewApprovalRequest(true, "approved"), 100L);

        assertFalse(response.isSuccess());
        assertFalse(clinic.getApproved());
        assertFalse(clinic.getEnabled());
        assertTrue(clinic.getApprovalBy() == null);
        assertTrue(clinic.getApprovalDate() == null);
        assertFalse(user.isEnabled());
        verify(approvalRequestRepository, never()).save(any(UserApprovalRequest.class));
        verify(userRepository, never()).save(any());
        verify(clinicRepository, never()).save(any());
    }

    @Test
    void clinicApprovalEnablesAlreadyVerifiedAdministratorAndClinic() {
        User user = pendingUser(Role.CLINIC_ADMIN, true);
        UserApprovalRequest request = pendingRequest(Role.CLINIC_ADMIN, 9L);
        Clinic clinic = pendingClinic(user);
        stubClinicAdminReview(user, request, clinic);

        ApiResponse<ApprovalRequestResponse> response = userApprovalService
                .reviewApprovalRequest(7, new ReviewApprovalRequest(true, "approved"), 100L);

        assertTrue(response.isSuccess());
        assertTrue(user.isEnabled());
        assertTrue(clinic.getApproved());
        assertTrue(clinic.getEnabled());
        assertEquals(100L, clinic.getApprovalBy());
    }

    @Test
    void clinicRejectionReleasesPendingClinicIdentity() {
        User user = pendingUser(Role.CLINIC_ADMIN, true);
        user.setClinicName("Reserved Clinic");
        UserApprovalRequest request = pendingRequest(Role.CLINIC_ADMIN, 9L);
        Clinic clinic = pendingClinic(user);
        stubClinicAdminReview(user, request, clinic);

        ApiResponse<ApprovalRequestResponse> response = userApprovalService
                .reviewApprovalRequest(7, new ReviewApprovalRequest(false, "rejected"), 100L);

        assertTrue(response.isSuccess());
        assertEquals(User.ApprovalStatus.REJECTED, user.getApprovalStatus());
        assertFalse(user.isEnabled());
        assertTrue(user.getClinicId() == null);
        assertTrue(user.getClinicName() == null);
        assertTrue(request.getClinicId() == null);
        assertTrue(response.getDataObject().getClinicId() == null);
        verify(clinicRepository).delete(clinic);
        verify(clinicRepository, never()).save(any());
    }

    @Test
    void clinicApprovalRejectsAdministratorOwnershipMismatchBeforeMutation() {
        User applicant = pendingUser(Role.CLINIC_ADMIN, true);
        User anotherAdmin = pendingUser(Role.CLINIC_ADMIN, true);
        anotherAdmin.setId(99L);
        UserApprovalRequest request = pendingRequest(Role.CLINIC_ADMIN, 9L);
        Clinic clinic = pendingClinic(anotherAdmin);
        stubClinicAdminReview(applicant, request, clinic);

        ApiResponse<ApprovalRequestResponse> response = userApprovalService
                .reviewApprovalRequest(7, new ReviewApprovalRequest(true, "approved"), 100L);

        assertFalse(response.isSuccess());
        assertFalse(clinic.getApproved());
        assertFalse(applicant.isEnabled());
        verify(approvalRequestRepository, never()).save(any(UserApprovalRequest.class));
        verify(userRepository, never()).save(any());
    }

    private void stubStaffReview(User applicant, UserApprovalRequest request) {
        User reviewer = activeClinicAdminReviewer();
        when(approvalRequestRepository.findById(7)).thenReturn(Optional.of(request));
        when(userRepository.findById(42L)).thenReturn(Optional.of(applicant));
        when(userRepository.findById(100L)).thenReturn(Optional.of(reviewer));
        when(clinicRepository.findById(9L)).thenReturn(Optional.of(activeClinic(reviewer)));
    }

    private void stubClinicAdminReview(User applicant, UserApprovalRequest request, Clinic clinic) {
        when(approvalRequestRepository.findById(7)).thenReturn(Optional.of(request));
        when(userRepository.findById(42L)).thenReturn(Optional.of(applicant));
        when(userRepository.findById(100L)).thenReturn(Optional.of(activeSystemAdminReviewer()));
        when(clinicRepository.findById(9L)).thenReturn(Optional.of(clinic));
    }

    private User activeClinicAdminReviewer() {
        return User.builder()
                .id(100L)
                .email("clinic-admin@example.com")
                .firstName("Clinic")
                .lastName("Admin")
                .password("encoded-password")
                .provider(AuthProvider.LOCAL)
                .roles(new HashSet<>(Set.of(Role.CLINIC_ADMIN)))
                .clinicId(9L)
                .approvalStatus(User.ApprovalStatus.APPROVED)
                .approvedBy("1")
                .approvalDate(LocalDateTime.now())
                .emailVerified(true)
                .enabled(true)
                .accountNonExpired(true)
                .credentialsNonExpired(true)
                .accountNonLocked(true)
                .build();
    }

    private User activeSystemAdminReviewer() {
        return User.builder()
                .id(100L)
                .email("system-admin@example.com")
                .firstName("System")
                .lastName("Admin")
                .password("encoded-password")
                .provider(AuthProvider.LOCAL)
                .roles(new HashSet<>(Set.of(Role.SYSTEM_ADMIN)))
                .emailVerified(true)
                .enabled(true)
                .accountNonExpired(true)
                .credentialsNonExpired(true)
                .accountNonLocked(true)
                .build();
    }

    private Clinic activeClinic(User admin) {
        return Clinic.builder()
                .id(9L)
                .name("Active Clinic")
                .admin(admin)
                .approved(true)
                .enabled(true)
                .build();
    }

    private User pendingUser(Role role, boolean emailVerified) {
        return User.builder()
                .id(42L)
                .email("applicant@example.com")
                .firstName("Pending")
                .lastName("Applicant")
                .password("encoded-password")
                .provider(AuthProvider.LOCAL)
                .roles(new HashSet<>(Set.of(role)))
                .clinicId(9L)
                .approvalStatus(User.ApprovalStatus.PENDING)
                .emailVerified(emailVerified)
                .enabled(false)
                .build();
    }

    private UserApprovalRequest pendingRequest(Role role, Long clinicId) {
        return UserApprovalRequest.builder()
                .id(7)
                .userId(42L)
                .requestedRole(role)
                .clinicId(clinicId)
                .status(User.ApprovalStatus.PENDING)
                .build();
    }

    private Clinic pendingClinic(User admin) {
        return Clinic.builder()
                .id(9L)
                .name("Pending Clinic")
                .admin(admin)
                .approved(false)
                .enabled(false)
                .build();
    }
}
