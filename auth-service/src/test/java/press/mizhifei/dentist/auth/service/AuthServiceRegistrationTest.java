package press.mizhifei.dentist.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import press.mizhifei.dentist.auth.client.NotificationServiceClient;
import press.mizhifei.dentist.auth.dto.ApiResponse;
import press.mizhifei.dentist.auth.dto.ApprovalRequestResponse;
import press.mizhifei.dentist.auth.dto.SignUpRequest;
import press.mizhifei.dentist.auth.dto.SignUpStaffRequest;
import press.mizhifei.dentist.auth.model.AuthProvider;
import press.mizhifei.dentist.auth.model.Clinic;
import press.mizhifei.dentist.auth.model.Role;
import press.mizhifei.dentist.auth.model.User;
import press.mizhifei.dentist.auth.repository.ClinicRepository;
import press.mizhifei.dentist.auth.repository.UserRepository;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceRegistrationTest {

    private UserRepository userRepository;
    private ClinicRepository clinicRepository;
    private NotificationServiceClient notificationServiceClient;
    private UserApprovalService userApprovalService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        clinicRepository = mock(ClinicRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        notificationServiceClient = mock(NotificationServiceClient.class);
        userApprovalService = mock(UserApprovalService.class);
        authService = new AuthService(
                userRepository,
                clinicRepository,
                passwordEncoder,
                notificationServiceClient,
                userApprovalService,
                mock(AuthSessionService.class));
        ReflectionTestUtils.setField(authService, "codeExpiryMinutes", 10L);
        ReflectionTestUtils.setField(authService, "verificationCodePepper", "test-pepper");
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        when(notificationServiceClient.sendVerificationEmail(any())).thenReturn(ResponseEntity.ok("sent"));
    }

    @Test
    void publicSignupAlwaysCreatesPatientRegardlessOfSubmittedRole() {
        when(userRepository.findByEmail("patient@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SignUpRequest request = new SignUpRequest(
                "Pat",
                "Ient",
                "patient@example.com",
                "secure-password",
                "SYSTEM_ADMIN");

        ApiResponse<String> response = authService.registerUser(request);

        assertTrue(response.isSuccess());
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertEquals(1, userCaptor.getValue().getRoles().size());
        assertTrue(userCaptor.getValue().getRoles().contains(Role.PATIENT));
        assertFalse(userCaptor.getValue().getRoles().contains(Role.SYSTEM_ADMIN));
    }

    @Test
    void publicSignupDoesNotRewriteDisabledPrivilegedAccount() {
        User disabledAdmin = User.builder()
                .email("admin@example.com")
                .firstName("Existing")
                .lastName("Admin")
                .password("existing-password")
                .provider(AuthProvider.LOCAL)
                .roles(Set.of(Role.SYSTEM_ADMIN))
                .enabled(false)
                .emailVerified(false)
                .build();
        when(userRepository.findByEmail("admin@example.com"))
                .thenReturn(Optional.of(disabledAdmin));

        ApiResponse<String> response = authService.registerUser(new SignUpRequest(
                "Attacker",
                "Controlled",
                "admin@example.com",
                "replacement-password",
                "PATIENT"));

        assertFalse(response.isSuccess());
        assertEquals("Email is already taken!", response.getMessage());
        assertEquals(Set.of(Role.SYSTEM_ADMIN), disabledAdmin.getRoles());
        assertEquals("existing-password", disabledAdmin.getPassword());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(notificationServiceClient);
    }

    @Test
    void publicSignupRetryDoesNotMutateUnverifiedLocalPatientRegistration() {
        User pendingPatient = User.builder()
                .id(42L)
                .email("pending@example.com")
                .firstName("Old")
                .lastName("Name")
                .password("old-password")
                .provider(AuthProvider.LOCAL)
                .roles(Set.of(Role.PATIENT))
                .enabled(false)
                .emailVerified(false)
                .build();
        when(userRepository.findByEmail("pending@example.com"))
                .thenReturn(Optional.of(pendingPatient));
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApiResponse<String> response = authService.registerUser(new SignUpRequest(
                "Updated",
                "Patient",
                "pending@example.com",
                "replacement-password",
                "SYSTEM_ADMIN"));

        assertTrue(response.isSuccess());
        assertEquals(Set.of(Role.PATIENT), pendingPatient.getRoles());
        assertEquals("old-password", pendingPatient.getPassword());
        assertEquals("Old", pendingPatient.getFirstName());
        assertEquals("Name", pendingPatient.getLastName());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(notificationServiceClient);
    }

    @Test
    void publicSignupDoesNotOverwriteDisabledFederatedIdentity() {
        User federatedPatient = User.builder()
                .email("federated@example.com")
                .firstName("Federated")
                .lastName("Patient")
                .provider(AuthProvider.GOOGLE)
                .providerId("google-subject")
                .roles(Set.of(Role.PATIENT))
                .enabled(false)
                .emailVerified(true)
                .build();
        when(userRepository.findByEmail("federated@example.com"))
                .thenReturn(Optional.of(federatedPatient));

        ApiResponse<String> response = authService.registerUser(new SignUpRequest(
                "Attacker",
                "Controlled",
                "federated@example.com",
                "replacement-password",
                "PATIENT"));

        assertFalse(response.isSuccess());
        assertEquals(AuthProvider.GOOGLE, federatedPatient.getProvider());
        assertEquals("google-subject", federatedPatient.getProviderId());
        verify(userRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "CLINIC_ADMIN", "PATIENT", "not-a-role"})
    void publicStaffSignupRejectsPrivilegedAndInvalidRoles(String role) {
        SignUpStaffRequest request = staffRequest(role);

        ApiResponse<String> response = authService.registerStaff(request);

        assertFalse(response.isSuccess());
        assertEquals("Invalid staff role", response.getMessage());
        verifyNoInteractions(userRepository);
        verify(userApprovalService, never()).createApprovalRequest(anyLong(), anyString());
    }

    @Test
    void publicStaffSignupRejectsUnknownOrUnapprovedClinic() {
        when(clinicRepository.findById(9L)).thenReturn(Optional.of(
                Clinic.builder().id(9L).name("Test Clinic").enabled(true).approved(false).build()));

        ApiResponse<String> response = authService.registerStaff(staffRequest("DENTIST"));

        assertFalse(response.isSuccess());
        assertEquals("Unable to register staff", response.getMessage());
        verify(userRepository, never()).save(any());
        verify(userApprovalService, never()).createApprovalRequest(anyLong(), anyString());
    }

    @Test
    void publicStaffSignupAllowsDentistRoleForApproval() {
        when(clinicRepository.findById(9L)).thenReturn(Optional.of(activeClinic()));
        when(userRepository.findByEmail("staff@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(42L);
            return user;
        });
        when(userApprovalService.createApprovalRequest(anyLong(), anyString()))
                .thenReturn(ApiResponse.success(ApprovalRequestResponse.builder().id(7).build()));

        ApiResponse<String> response = authService.registerStaff(staffRequest("DENTIST"));

        assertTrue(response.isSuccess());
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertEquals(1, userCaptor.getValue().getRoles().size());
        assertTrue(userCaptor.getValue().getRoles().contains(Role.DENTIST));
        assertEquals(9L, userCaptor.getValue().getClinicId());
        assertEquals("Authoritative Clinic Name", userCaptor.getValue().getClinicName());
        verify(userApprovalService).createApprovalRequest(42L, "Clinic staff sign up for Authoritative Clinic Name");
    }

    private Clinic activeClinic() {
        User clinicAdmin = User.builder()
                .id(100L)
                .email("clinic-admin@example.com")
                .firstName("Clinic")
                .lastName("Admin")
                .roles(Set.of(Role.CLINIC_ADMIN))
                .clinicId(9L)
                .emailVerified(true)
                .approvalStatus(User.ApprovalStatus.APPROVED)
                .approvedBy("1")
                .approvalDate(java.time.LocalDateTime.now())
                .enabled(true)
                .build();
        return Clinic.builder()
                .id(9L)
                .name("Authoritative Clinic Name")
                .admin(clinicAdmin)
                .enabled(true)
                .approved(true)
                .build();
    }

    private SignUpStaffRequest staffRequest(String role) {
        return new SignUpStaffRequest(
                9L,
                "Test Clinic",
                "Dental",
                "Staff",
                "staff@example.com",
                "secure-password",
                role);
    }
}
