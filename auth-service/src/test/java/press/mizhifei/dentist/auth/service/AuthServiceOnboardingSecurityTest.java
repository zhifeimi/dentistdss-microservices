package press.mizhifei.dentist.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import press.mizhifei.dentist.auth.audit.AuditEventPublisher;
import press.mizhifei.dentist.auth.client.NotificationServiceClient;
import press.mizhifei.dentist.auth.dto.ApiResponse;
import press.mizhifei.dentist.auth.dto.ApprovalRequestResponse;
import press.mizhifei.dentist.auth.dto.SignUpClinicAdminRequest;
import press.mizhifei.dentist.auth.dto.SignUpStaffRequest;
import press.mizhifei.dentist.auth.model.AuthProvider;
import press.mizhifei.dentist.auth.model.Clinic;
import press.mizhifei.dentist.auth.model.Role;
import press.mizhifei.dentist.auth.model.User;
import press.mizhifei.dentist.auth.repository.ClinicRepository;
import press.mizhifei.dentist.auth.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceOnboardingSecurityTest {

    private UserRepository userRepository;
    private ClinicRepository clinicRepository;
    private PasswordEncoder passwordEncoder;
    private NotificationServiceClient notificationServiceClient;
    private UserApprovalService userApprovalService;
    private AuthSessionService authSessionService;
    private AuditEventPublisher auditEventPublisher;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        clinicRepository = mock(ClinicRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        notificationServiceClient = mock(NotificationServiceClient.class);
        userApprovalService = mock(UserApprovalService.class);
        authSessionService = mock(AuthSessionService.class);
        auditEventPublisher = mock(AuditEventPublisher.class);
        authService = new AuthService(
                userRepository,
                clinicRepository,
                passwordEncoder,
                notificationServiceClient,
                userApprovalService,
                authSessionService,
                auditEventPublisher);
        ReflectionTestUtils.setField(authService, "codeExpiryMinutes", 10L);
        ReflectionTestUtils.setField(authService, "verificationCodePepper", "test-pepper");
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        when(notificationServiceClient.sendVerificationEmail(any()))
                .thenReturn(ResponseEntity.ok("sent"));
    }

    @Test
    void staffSignupDoesNotRewriteDisabledPrivilegedAccount() {
        User disabledAdmin = pendingUser(Role.SYSTEM_ADMIN, 42L, 9L);
        disabledAdmin.setPassword("existing-password");
        when(clinicRepository.findById(9L)).thenReturn(Optional.of(activeClinic()));
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(disabledAdmin));

        ApiResponse<String> response = authService.registerStaff(staffRequest("DENTIST"));

        assertFalse(response.isSuccess());
        assertEquals("Unable to register staff", response.getMessage());
        assertEquals(Set.of(Role.SYSTEM_ADMIN), disabledAdmin.getRoles());
        assertEquals("existing-password", disabledAdmin.getPassword());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(notificationServiceClient);
    }

    @Test
    void staffSignupDoesNotRewriteDisabledPatient() {
        User disabledPatient = pendingUser(Role.PATIENT, 42L, null);
        disabledPatient.setPassword("patient-password");
        when(clinicRepository.findById(9L)).thenReturn(Optional.of(activeClinic()));
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(disabledPatient));

        ApiResponse<String> response = authService.registerStaff(staffRequest("DENTIST"));

        assertFalse(response.isSuccess());
        assertEquals(Set.of(Role.PATIENT), disabledPatient.getRoles());
        assertEquals("patient-password", disabledPatient.getPassword());
        verify(userRepository, never()).save(any());
    }

    @Test
    void staffSignupDoesNotRewriteFederatedIdentity() {
        User federatedDentist = pendingUser(Role.DENTIST, 42L, 9L);
        federatedDentist.setProvider(AuthProvider.GOOGLE);
        federatedDentist.setProviderId("google-subject");
        when(clinicRepository.findById(9L)).thenReturn(Optional.of(activeClinic()));
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(federatedDentist));

        ApiResponse<String> response = authService.registerStaff(staffRequest("DENTIST"));

        assertFalse(response.isSuccess());
        assertEquals(AuthProvider.GOOGLE, federatedDentist.getProvider());
        assertEquals("google-subject", federatedDentist.getProviderId());
        verify(userRepository, never()).save(any());
        verify(userApprovalService, never())
                .createApprovalRequest(anyLong(), anyString());
    }

    @Test
    void staffSignupRejectsDifferentClinicRoleAndReviewedState() {
        User unrelatedStaff = pendingUser(Role.RECEPTIONIST, 42L, 10L);
        unrelatedStaff.setApprovalStatus(User.ApprovalStatus.REJECTED);
        unrelatedStaff.setApprovalRejectionReason("rejected");
        when(clinicRepository.findById(9L)).thenReturn(Optional.of(activeClinic()));
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(unrelatedStaff));

        ApiResponse<String> response = authService.registerStaff(staffRequest("DENTIST"));

        assertFalse(response.isSuccess());
        assertEquals(Set.of(Role.RECEPTIONIST), unrelatedStaff.getRoles());
        assertEquals(10L, unrelatedStaff.getClinicId());
        assertEquals(User.ApprovalStatus.REJECTED, unrelatedStaff.getApprovalStatus());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(notificationServiceClient);
    }

    @Test
    void staffSignupRejectsPreviouslyApprovedAccount() {
        User approvedStaff = pendingUser(Role.DENTIST, 42L, 9L);
        approvedStaff.setApprovalStatus(User.ApprovalStatus.APPROVED);
        approvedStaff.setApprovedBy("100");
        approvedStaff.setApprovalDate(LocalDateTime.now());
        when(clinicRepository.findById(9L)).thenReturn(Optional.of(activeClinic()));
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(approvedStaff));

        ApiResponse<String> response = authService.registerStaff(staffRequest("DENTIST"));

        assertFalse(response.isSuccess());
        assertEquals(User.ApprovalStatus.APPROVED, approvedStaff.getApprovalStatus());
        assertEquals("100", approvedStaff.getApprovedBy());
        verify(userRepository, never()).save(any());
    }

    @Test
    void staffSignupRejectsLockedPendingAccountInsteadOfResettingIt() {
        User lockedStaff = pendingUser(Role.DENTIST, 42L, 9L);
        lockedStaff.setAccountNonLocked(false);
        when(clinicRepository.findById(9L)).thenReturn(Optional.of(activeClinic()));
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(lockedStaff));

        ApiResponse<String> response = authService.registerStaff(staffRequest("DENTIST"));

        assertFalse(response.isSuccess());
        assertFalse(lockedStaff.isAccountNonLocked());
        verify(userRepository, never()).save(any());
    }

    @Test
    void staffSignupRetryPreservesMatchingPendingApplicant() {
        User pendingStaff = pendingUser(Role.DENTIST, 42L, 9L);
        pendingStaff.setVerificationCode("existing-code-hash");
        LocalDateTime existingExpiry = LocalDateTime.now().plusMinutes(5);
        pendingStaff.setVerificationCodeExpiry(existingExpiry);
        when(clinicRepository.findById(9L)).thenReturn(Optional.of(activeClinic()));
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(pendingStaff));
        when(userApprovalService.hasMatchingPendingApprovalRequest(
                42L, Role.DENTIST, 9L)).thenReturn(true);

        ApiResponse<String> response = authService.registerStaff(staffRequest("DENTIST"));

        assertTrue(response.isSuccess());
        assertEquals(Set.of(Role.DENTIST), pendingStaff.getRoles());
        assertEquals(9L, pendingStaff.getClinicId());
        assertEquals(User.ApprovalStatus.PENDING, pendingStaff.getApprovalStatus());
        assertEquals("Existing", pendingStaff.getFirstName());
        assertEquals("Applicant", pendingStaff.getLastName());
        assertEquals("existing-password", pendingStaff.getPassword());
        assertEquals("existing-code-hash", pendingStaff.getVerificationCode());
        assertEquals(existingExpiry, pendingStaff.getVerificationCodeExpiry());
        verify(userRepository, never()).save(any());
        verify(userApprovalService, never()).createApprovalRequest(anyLong(), anyString());
        verifyNoInteractions(notificationServiceClient);
    }

    @Test
    void staffSignupRejectsPendingUserWithoutMatchingApprovalRequest() {
        User pendingStaff = pendingUser(Role.DENTIST, 42L, 9L);
        when(clinicRepository.findById(9L)).thenReturn(Optional.of(activeClinic()));
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(pendingStaff));
        when(userApprovalService.hasMatchingPendingApprovalRequest(
                42L, Role.DENTIST, 9L)).thenReturn(false);

        ApiResponse<String> response = authService.registerStaff(staffRequest("DENTIST"));

        assertFalse(response.isSuccess());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(notificationServiceClient);
    }

    @Test
    void clinicAdminSignupDoesNotRewriteFederatedApplicant() {
        User applicant = pendingUser(Role.CLINIC_ADMIN, 42L, 9L);
        applicant.setProvider(AuthProvider.GOOGLE);
        applicant.setProviderId("google-subject");
        Clinic clinic = pendingClinic(9L, applicant);
        when(userRepository.findByEmail("applicant@example.com"))
                .thenReturn(Optional.of(applicant));
        when(clinicRepository.findByEmail("clinic@example.com"))
                .thenReturn(Optional.of(clinic));

        ApiResponse<String> response = authService.registerClinicAdmin(clinicAdminRequest());

        assertFalse(response.isSuccess());
        assertEquals(AuthProvider.GOOGLE, applicant.getProvider());
        assertEquals("google-subject", applicant.getProviderId());
        assertSame(applicant, clinic.getAdmin());
        verify(userRepository, never()).save(any());
        verify(clinicRepository, never()).save(any());
    }

    @Test
    void clinicAdminSignupCreatesOneNewCoherentPair() {
        when(userRepository.findByEmail("applicant@example.com"))
                .thenReturn(Optional.empty());
        when(clinicRepository.findByEmail("clinic@example.com"))
                .thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(42L);
            return user;
        });
        when(clinicRepository.save(any(Clinic.class))).thenAnswer(invocation -> {
            Clinic clinic = invocation.getArgument(0);
            clinic.setId(9L);
            return clinic;
        });
        when(userApprovalService.createApprovalRequest(anyLong(), anyString()))
                .thenReturn(successfulApproval());

        ApiResponse<String> response = authService.registerClinicAdmin(clinicAdminRequest());

        assertTrue(response.isSuccess());
        ArgumentCaptor<Clinic> clinicCaptor = ArgumentCaptor.forClass(Clinic.class);
        verify(clinicRepository).save(clinicCaptor.capture());
        Clinic savedClinic = clinicCaptor.getValue();
        assertEquals(42L, savedClinic.getAdmin().getId());
        assertFalse(savedClinic.getApproved());
        assertFalse(savedClinic.getEnabled());
        assertEquals(User.ApprovalStatus.PENDING,
                savedClinic.getAdmin().getApprovalStatus());
        verify(userApprovalService).createApprovalRequest(
                42L, "Clinic admin sign up for New Clinic");
        verify(notificationServiceClient).sendVerificationEmail(any());
    }

    @Test
    void clinicAdminSignupRejectsNewUserClaimingExistingClinic() {
        User currentAdmin = approvedClinicAdmin(100L, 9L);
        Clinic clinic = pendingClinic(9L, currentAdmin);
        clinic.setEnabled(true);
        clinic.setApproved(true);
        when(userRepository.findByEmail("applicant@example.com"))
                .thenReturn(Optional.empty());
        when(clinicRepository.findByEmail("clinic@example.com"))
                .thenReturn(Optional.of(clinic));

        ApiResponse<String> response = authService.registerClinicAdmin(clinicAdminRequest());

        assertFalse(response.isSuccess());
        assertSame(currentAdmin, clinic.getAdmin());
        verify(userRepository, never()).save(any());
        verify(clinicRepository, never()).save(any());
        verifyNoInteractions(notificationServiceClient);
    }

    @Test
    void clinicAdminSignupRejectsExistingUserWithoutClinic() {
        User pendingAdmin = pendingUser(Role.CLINIC_ADMIN, 42L, 9L);
        when(userRepository.findByEmail("applicant@example.com"))
                .thenReturn(Optional.of(pendingAdmin));
        when(clinicRepository.findByEmail("clinic@example.com"))
                .thenReturn(Optional.empty());

        ApiResponse<String> response = authService.registerClinicAdmin(clinicAdminRequest());

        assertFalse(response.isSuccess());
        verify(userRepository, never()).save(any());
        verify(clinicRepository, never()).save(any());
    }

    @Test
    void clinicAdminSignupDoesNotReplaceAnotherAdministrator() {
        User applicant = pendingUser(Role.CLINIC_ADMIN, 42L, 9L);
        User currentAdmin = approvedClinicAdmin(100L, 9L);
        Clinic clinic = pendingClinic(9L, currentAdmin);
        when(userRepository.findByEmail("applicant@example.com"))
                .thenReturn(Optional.of(applicant));
        when(clinicRepository.findByEmail("clinic@example.com"))
                .thenReturn(Optional.of(clinic));

        ApiResponse<String> response = authService.registerClinicAdmin(clinicAdminRequest());

        assertFalse(response.isSuccess());
        assertSame(currentAdmin, clinic.getAdmin());
        verify(userRepository, never()).save(any());
        verify(clinicRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"enabled", "approved", "approvalBy", "approvalDate"})
    void clinicAdminSignupRejectsProtectedClinicLifecycleState(String field) {
        User applicant = pendingUser(Role.CLINIC_ADMIN, 42L, 9L);
        Clinic clinic = pendingClinic(9L, applicant);
        switch (field) {
            case "enabled" -> clinic.setEnabled(true);
            case "approved" -> clinic.setApproved(true);
            case "approvalBy" -> clinic.setApprovalBy(100L);
            case "approvalDate" -> clinic.setApprovalDate(LocalDateTime.now());
            default -> throw new IllegalArgumentException(field);
        }
        when(userRepository.findByEmail("applicant@example.com"))
                .thenReturn(Optional.of(applicant));
        when(clinicRepository.findByEmail("clinic@example.com"))
                .thenReturn(Optional.of(clinic));

        ApiResponse<String> response = authService.registerClinicAdmin(clinicAdminRequest());

        assertFalse(response.isSuccess());
        assertSame(applicant, clinic.getAdmin());
        verify(userRepository, never()).save(any());
        verify(clinicRepository, never()).save(any());
        verify(userApprovalService, never()).createApprovalRequest(anyLong(), anyString());
    }

    @Test
    void clinicAdminSignupRejectsOrphanPendingClinic() {
        User applicant = pendingUser(Role.CLINIC_ADMIN, 42L, 9L);
        Clinic orphanClinic = pendingClinic(9L, null);
        when(userRepository.findByEmail("applicant@example.com"))
                .thenReturn(Optional.of(applicant));
        when(clinicRepository.findByEmail("clinic@example.com"))
                .thenReturn(Optional.of(orphanClinic));

        ApiResponse<String> response = authService.registerClinicAdmin(clinicAdminRequest());

        assertFalse(response.isSuccess());
        verify(userRepository, never()).save(any());
        verify(clinicRepository, never()).save(any());
    }

    @Test
    void clinicAdminSignupRetryPreservesMatchingPendingPair() {
        User applicant = pendingUser(Role.CLINIC_ADMIN, 42L, 9L);
        applicant.setFirstName("Original");
        applicant.setLastName("Administrator");
        applicant.setVerificationCode("existing-code-hash");
        LocalDateTime existingExpiry = LocalDateTime.now().plusMinutes(5);
        applicant.setVerificationCodeExpiry(existingExpiry);
        Clinic clinic = pendingClinic(9L, applicant);
        clinic.setName("Original Clinic");
        clinic.setAddress("Original address");
        clinic.setPhoneNumber("0200000000");
        when(userRepository.findByEmail("applicant@example.com"))
                .thenReturn(Optional.of(applicant));
        when(clinicRepository.findByEmail("clinic@example.com"))
                .thenReturn(Optional.of(clinic));
        when(userApprovalService.hasMatchingPendingApprovalRequest(
                42L, Role.CLINIC_ADMIN, 9L)).thenReturn(true);

        ApiResponse<String> response = authService.registerClinicAdmin(clinicAdminRequest());

        assertTrue(response.isSuccess());
        assertSame(applicant, clinic.getAdmin());
        assertEquals(Set.of(Role.CLINIC_ADMIN), applicant.getRoles());
        assertEquals(User.ApprovalStatus.PENDING, applicant.getApprovalStatus());
        assertEquals("Original", applicant.getFirstName());
        assertEquals("Administrator", applicant.getLastName());
        assertEquals("existing-password", applicant.getPassword());
        assertEquals("existing-code-hash", applicant.getVerificationCode());
        assertEquals(existingExpiry, applicant.getVerificationCodeExpiry());
        assertEquals("Original Clinic", clinic.getName());
        assertEquals("Original address", clinic.getAddress());
        assertEquals("0200000000", clinic.getPhoneNumber());
        assertFalse(clinic.getApproved());
        assertFalse(clinic.getEnabled());
        verify(userRepository, never()).save(any());
        verify(clinicRepository, never()).save(any());
        verify(userApprovalService, never()).createApprovalRequest(anyLong(), anyString());
        verifyNoInteractions(notificationServiceClient);
    }

    @Test
    void mailboxVerificationReplacesFirstWritersPatientPassword() {
        User pendingPatient = pendingUser(Role.PATIENT, 42L, null);
        pendingPatient.setApprovalStatus(null);
        pendingPatient.setPassword("attacker-password");
        setVerificationCode(pendingPatient, "123456");
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(pendingPatient));
        when(userRepository.save(pendingPatient)).thenReturn(pendingPatient);

        ApiResponse<String> response = authService.verifyEmailByCode(
                "staff@example.com", "123456", "VictimStrong1!");

        assertTrue(response.isSuccess());
        assertTrue(pendingPatient.isEmailVerified());
        assertTrue(pendingPatient.isEnabled());
        assertEquals("encoded-password", pendingPatient.getPassword());
        verify(passwordEncoder).encode("VictimStrong1!");
        verify(authSessionService).publishSecurityChangeAndRevokeAll(pendingPatient);
    }

    @Test
    void verificationRejectsWeakReplacementPasswordBeforeMutation() {
        User pendingPatient = pendingUser(Role.PATIENT, 42L, null);
        pendingPatient.setApprovalStatus(null);
        setVerificationCode(pendingPatient, "123456");

        ApiResponse<String> response = authService.verifyEmailByCode(
                "staff@example.com", "123456", "weakpass");

        assertFalse(response.isSuccess());
        assertFalse(pendingPatient.isEmailVerified());
        assertFalse(pendingPatient.isEnabled());
        verifyNoInteractions(userRepository);
        verifyNoInteractions(authSessionService);
    }

    @Test
    void verifiedStaffRemainsDisabledUntilApproval() {
        User pendingStaff = pendingUser(Role.DENTIST, 42L, 9L);
        setVerificationCode(pendingStaff, "123456");
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(pendingStaff));
        when(userApprovalService.hasMatchingPendingApprovalRequest(
                42L, Role.DENTIST, 9L)).thenReturn(true);
        when(clinicRepository.findById(9L)).thenReturn(Optional.of(activeClinic()));
        when(userRepository.save(pendingStaff)).thenReturn(pendingStaff);

        ApiResponse<String> response = authService.verifyEmailByCode(
                "staff@example.com", "123456", "FinalStrong1!");

        assertTrue(response.isSuccess());
        assertTrue(pendingStaff.isEmailVerified());
        assertFalse(pendingStaff.isEnabled());
        assertEquals("encoded-password", pendingStaff.getPassword());
        verify(passwordEncoder).encode("FinalStrong1!");
        verify(authSessionService).publishSecurityChangeAndRevokeAll(pendingStaff);
    }

    @Test
    void verifiedClinicAdminRemainsDisabledAndClinicPendingUntilApproval() {
        User pendingAdmin = pendingUser(Role.CLINIC_ADMIN, 42L, 9L);
        setVerificationCode(pendingAdmin, "123456");
        Clinic pendingClinic = pendingClinic(9L, pendingAdmin);
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(pendingAdmin));
        when(userApprovalService.hasMatchingPendingApprovalRequest(
                42L, Role.CLINIC_ADMIN, 9L)).thenReturn(true);
        when(clinicRepository.findById(9L)).thenReturn(Optional.of(pendingClinic));
        when(userRepository.save(pendingAdmin)).thenReturn(pendingAdmin);

        ApiResponse<String> response = authService.verifyEmailByCode(
                "staff@example.com", "123456", "FinalStrong1!");

        assertTrue(response.isSuccess());
        assertTrue(pendingAdmin.isEmailVerified());
        assertFalse(pendingAdmin.isEnabled());
        assertFalse(pendingClinic.getApproved());
        assertFalse(pendingClinic.getEnabled());
        verify(clinicRepository, never()).save(any());
    }

    @Test
    void verificationDoesNotActivateLegacyApprovedUnverifiedStaff() {
        User approvedStaff = pendingUser(Role.DENTIST, 42L, 9L);
        approvedStaff.setApprovalStatus(User.ApprovalStatus.APPROVED);
        approvedStaff.setApprovedBy("100");
        approvedStaff.setApprovalDate(LocalDateTime.now());
        setVerificationCode(approvedStaff, "123456");
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(approvedStaff));

        ApiResponse<String> response = authService.verifyEmailByCode(
                "staff@example.com", "123456", "FinalStrong1!");

        assertFalse(response.isSuccess());
        assertFalse(approvedStaff.isEmailVerified());
        assertFalse(approvedStaff.isEnabled());
        verify(userRepository, never()).save(any());
    }

    @Test
    void verificationDoesNotActivateLegacyApprovedUnverifiedClinicAdmin() {
        User approvedAdmin = pendingUser(Role.CLINIC_ADMIN, 42L, 9L);
        approvedAdmin.setApprovalStatus(User.ApprovalStatus.APPROVED);
        approvedAdmin.setApprovedBy("100");
        approvedAdmin.setApprovalDate(LocalDateTime.now());
        setVerificationCode(approvedAdmin, "123456");
        Clinic approvedClinic = pendingClinic(9L, approvedAdmin);
        approvedClinic.setApprovalBy(100L);
        approvedClinic.setApprovalDate(LocalDateTime.now());
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(approvedAdmin));

        ApiResponse<String> response = authService.verifyEmailByCode(
                "staff@example.com", "123456", "FinalStrong1!");

        assertFalse(response.isSuccess());
        assertFalse(approvedAdmin.isEmailVerified());
        assertFalse(approvedAdmin.isEnabled());
        assertFalse(approvedClinic.getApproved());
        assertFalse(approvedClinic.getEnabled());
        verify(userRepository, never()).save(any());
        verify(clinicRepository, never()).save(any());
    }

    @Test
    void approvedStatusAloneDoesNotEnableIncoherentAccount() {
        User incoherentUser = pendingUser(Role.SYSTEM_ADMIN, 42L, null);
        incoherentUser.setApprovalStatus(User.ApprovalStatus.APPROVED);
        incoherentUser.setApprovedBy("100");
        incoherentUser.setApprovalDate(LocalDateTime.now());
        setVerificationCode(incoherentUser, "123456");
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(incoherentUser));

        ApiResponse<String> response = authService.verifyEmailByCode(
                "staff@example.com", "123456", "FinalStrong1!");

        assertFalse(response.isSuccess());
        assertFalse(incoherentUser.isEmailVerified());
        assertFalse(incoherentUser.isEnabled());
        verify(userRepository, never()).save(any());
    }

    @Test
    void patientVerificationRejectsMixedApprovalState() {
        User patient = pendingUser(Role.PATIENT, 42L, null);
        patient.setApprovalStatus(User.ApprovalStatus.APPROVED);
        patient.setApprovedBy("100");
        patient.setApprovalDate(LocalDateTime.now());
        setVerificationCode(patient, "123456");
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(patient));

        ApiResponse<String> response = authService.verifyEmailByCode(
                "staff@example.com", "123456", "FinalStrong1!");

        assertFalse(response.isSuccess());
        assertFalse(patient.isEmailVerified());
        assertFalse(patient.isEnabled());
        verify(userRepository, never()).save(any());
    }

    @Test
    void clinicAdminVerificationDoesNotReenableApprovedDisabledClinic() {
        User approvedAdmin = pendingUser(Role.CLINIC_ADMIN, 42L, 9L);
        approvedAdmin.setApprovalStatus(User.ApprovalStatus.APPROVED);
        approvedAdmin.setApprovedBy("100");
        approvedAdmin.setApprovalDate(LocalDateTime.now());
        setVerificationCode(approvedAdmin, "123456");
        Clinic disabledClinic = pendingClinic(9L, approvedAdmin);
        disabledClinic.setApproved(true);
        disabledClinic.setApprovalBy(100L);
        disabledClinic.setApprovalDate(LocalDateTime.now());
        when(userRepository.findByEmail("staff@example.com"))
                .thenReturn(Optional.of(approvedAdmin));

        ApiResponse<String> response = authService.verifyEmailByCode(
                "staff@example.com", "123456", "FinalStrong1!");

        assertFalse(response.isSuccess());
        assertFalse(approvedAdmin.isEmailVerified());
        assertFalse(approvedAdmin.isEnabled());
        assertTrue(disabledClinic.getApproved());
        assertFalse(disabledClinic.getEnabled());
        verify(userRepository, never()).save(any());
        verify(clinicRepository, never()).save(any());
    }

    private User pendingUser(Role role, Long userId, Long clinicId) {
        return User.builder()
                .id(userId)
                .email("staff@example.com")
                .firstName("Existing")
                .lastName("Applicant")
                .password("existing-password")
                .provider(AuthProvider.LOCAL)
                .roles(Set.of(role))
                .clinicId(clinicId)
                .clinicName("Authoritative Clinic Name")
                .approvalStatus(User.ApprovalStatus.PENDING)
                .emailVerified(false)
                .enabled(false)
                .accountNonExpired(true)
                .credentialsNonExpired(true)
                .accountNonLocked(true)
                .build();
    }

    private User approvedClinicAdmin(Long userId, Long clinicId) {
        return User.builder()
                .id(userId)
                .email("current-admin@example.com")
                .firstName("Current")
                .lastName("Admin")
                .provider(AuthProvider.LOCAL)
                .roles(Set.of(Role.CLINIC_ADMIN))
                .clinicId(clinicId)
                .approvalStatus(User.ApprovalStatus.APPROVED)
                .approvedBy("1")
                .approvalDate(LocalDateTime.now())
                .emailVerified(true)
                .enabled(true)
                .build();
    }

    private Clinic activeClinic() {
        return Clinic.builder()
                .id(9L)
                .name("Authoritative Clinic Name")
                .email("clinic@example.com")
                .admin(approvedClinicAdmin(100L, 9L))
                .approved(true)
                .enabled(true)
                .build();
    }

    private Clinic pendingClinic(Long clinicId, User admin) {
        return Clinic.builder()
                .id(clinicId)
                .name("Existing Clinic")
                .email("clinic@example.com")
                .admin(admin)
                .approved(false)
                .enabled(false)
                .build();
    }

    private SignUpStaffRequest staffRequest(String role) {
        return new SignUpStaffRequest(
                9L,
                "Ignored Clinic Name",
                "Dental",
                "Staff",
                "staff@example.com",
                "secure-password",
                role);
    }

    private SignUpClinicAdminRequest clinicAdminRequest() {
        return new SignUpClinicAdminRequest(
                "New Clinic",
                "1 Dental Street",
                "Wollongong",
                "NSW",
                "2500",
                "Australia",
                "0212345678",
                "clinic@example.com",
                "https://clinic.example.com",
                "Clinic",
                "Applicant",
                "applicant@example.com",
                "secure-password");
    }

    private ApiResponse<ApprovalRequestResponse> successfulApproval() {
        return ApiResponse.success(ApprovalRequestResponse.builder().id(7).build());
    }

    private void setVerificationCode(User user, String rawCode) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("test-pepper" + rawCode).getBytes(StandardCharsets.UTF_8));
            user.setVerificationCode(HexFormat.of().formatHex(digest));
            user.setVerificationCodeExpiry(LocalDateTime.now().plusMinutes(5));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
