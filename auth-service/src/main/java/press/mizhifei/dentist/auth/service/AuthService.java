package press.mizhifei.dentist.auth.service;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import press.mizhifei.dentist.auth.audit.AuditEventPublisher;
import press.mizhifei.dentist.auth.dto.ApiResponse;
import press.mizhifei.dentist.auth.dto.ApprovalRequestResponse;
import press.mizhifei.dentist.auth.dto.ChangePasswordRequest;
import press.mizhifei.dentist.auth.dto.SignUpClinicAdminRequest;
import press.mizhifei.dentist.auth.dto.SignUpRequest;
import press.mizhifei.dentist.auth.dto.SignUpStaffRequest;
import press.mizhifei.dentist.auth.dto.UserResponse;
import press.mizhifei.dentist.auth.model.AuthProvider;
import press.mizhifei.dentist.auth.model.Clinic;
import press.mizhifei.dentist.auth.model.Role;
import press.mizhifei.dentist.auth.model.User;
import press.mizhifei.dentist.auth.repository.ClinicRepository;
import press.mizhifei.dentist.auth.repository.UserRepository;
import press.mizhifei.dentist.auth.security.UserPrincipal;
import press.mizhifei.dentist.auth.client.NotificationServiceClient;
import press.mizhifei.dentist.auth.dto.VerificationEmailRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern STRONG_PASSWORD = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S{8,128}$");

    private final UserRepository userRepository;
    private final ClinicRepository clinicRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationServiceClient notificationServiceClient;
    private final UserApprovalService userApprovalService;
    private final AuthSessionService authSessionService;
    private final AuditEventPublisher auditEventPublisher;

    @Value("${app.email-verification.code-expiry-minutes}")
    private long codeExpiryMinutes;

    @Value("${app.email-verification.code-pepper}")
    private String verificationCodePepper;

    @Transactional
    public ApiResponse<String> registerUser(SignUpRequest signUpRequest) {
        Optional<User> existingUser = userRepository.findByEmail(signUpRequest.getEmail());
        if (existingUser.isPresent()) {
            if (!isResumablePatientRegistration(existingUser.get())) {
                return ApiResponse.error("Email is already taken!");
            }
            return ApiResponse.successMessage(
                    "User registered successfully. Please check your email to complete registration.");
        }

        // Generate verification token
        // String emailVerificationToken = generateVerificationToken();
        // LocalDateTime tokenExpiry =
        // LocalDateTime.now().plusMinutes(tokenExpiryMinutes);

        // Generate verification code
        String verificationCode = generateVerificationCode();
        LocalDateTime codeExpiry = LocalDateTime.now().plusMinutes(codeExpiryMinutes);

        User user = User.builder()
                .firstName(signUpRequest.getFirstName())
                .lastName(signUpRequest.getLastName())
                .email(signUpRequest.getEmail())
                .password(passwordEncoder.encode(signUpRequest.getPassword()))
                .roles(new HashSet<>(Collections.singleton(Role.PATIENT)))
                .provider(AuthProvider.LOCAL)
                .emailVerified(false)
                // .emailVerificationToken(emailVerificationToken)
                // .emailVerificationTokenExpiry(tokenExpiry)
                .verificationCode(hashVerificationCode(verificationCode))
                .verificationCodeExpiry(codeExpiry)
                .enabled(false) // User is not enabled until email is verified
                .accountNonExpired(true)
                .credentialsNonExpired(true)
                .accountNonLocked(true)
                .build();
        User savedUser = userRepository.save(user);

        auditEventPublisher.publish(
                "USER_REGISTERED",
                "user:" + savedUser.getId(),
                savedUser.getId(),
                null,
                Map.of("role", Role.PATIENT.name(),
                        "provider", savedUser.getProvider().name()));

        // Send verification token email
        // emailService.sendVerificationEmail(
        // savedUser.getEmail(),
        // emailVerificationToken
        // );
        notificationServiceClient.sendVerificationEmail(
                new VerificationEmailRequest(savedUser.getEmail(), verificationCode, "code"));

        return ApiResponse
                .successMessage("User registered successfully. Please check your email to complete registration.");
    }

    @Transactional
    public ApiResponse<String> registerStaff(SignUpStaffRequest signUpStaffRequest) {
        Role staffRole;
        try {
            staffRole = Role.fromString(signUpStaffRequest.getRole());
        } catch (IllegalArgumentException | NullPointerException ex) {
            return ApiResponse.error("Invalid staff role");
        }
        if (staffRole != Role.DENTIST && staffRole != Role.RECEPTIONIST) {
            return ApiResponse.error("Invalid staff role");
        }

        Clinic clinic = clinicRepository.findById(signUpStaffRequest.getClinicId())
                .filter(this::isActiveApprovedClinic)
                .orElse(null);
        if (clinic == null) {
            return ApiResponse.error("Unable to register staff");
        }

        Optional<User> existingUser = userRepository.findByEmail(signUpStaffRequest.getEmail());
        if (existingUser.isPresent()) {
            User pendingUser = existingUser.get();
            if (!isResumableStaffRegistration(pendingUser, staffRole, clinic)
                    || !userApprovalService.hasMatchingPendingApprovalRequest(
                            pendingUser.getId(), staffRole, clinic.getId())) {
                return ApiResponse.error("Unable to register staff");
            }
            return ApiResponse.successMessage(
                    "Staff registered successfully, waiting for email verification and system admin approval");
        }

        String verificationCode = generateVerificationCode();
        LocalDateTime codeExpiry = LocalDateTime.now().plusMinutes(codeExpiryMinutes);

        User user = User.builder()
                .firstName(signUpStaffRequest.getFirstName())
                .lastName(signUpStaffRequest.getLastName())
                .email(signUpStaffRequest.getEmail())
                .password(passwordEncoder.encode(signUpStaffRequest.getPassword()))
                .provider(AuthProvider.LOCAL)
                .roles(new HashSet<>(Collections.singleton(staffRole)))
                .emailVerified(false)
                .verificationCode(hashVerificationCode(verificationCode))
                .verificationCodeExpiry(codeExpiry)
                .clinicId(clinic.getId())
                .clinicName(clinic.getName())
                .approvalStatus(User.ApprovalStatus.PENDING)
                .enabled(false)
                .accountNonExpired(true)
                .credentialsNonExpired(true)
                .accountNonLocked(true)
                .build();
        User savedUser = userRepository.save(user);

        ApiResponse<ApprovalRequestResponse> approvalResponse = userApprovalService.createApprovalRequest(
                savedUser.getId(),
                "Clinic staff sign up for " + clinic.getName());
        if (!approvalResponse.isSuccess()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ApiResponse.error("Unable to register staff");
        }

        auditEventPublisher.publish(
                "USER_REGISTERED",
                "user:" + savedUser.getId(),
                savedUser.getId(),
                clinic.getId(),
                Map.of("role", staffRole.name(),
                        "provider", savedUser.getProvider().name()));

        notificationServiceClient.sendVerificationEmail(
                new VerificationEmailRequest(savedUser.getEmail(), verificationCode, "code"));

        return ApiResponse.successMessage(
                "Staff registered successfully, waiting for email verification and system admin approval");
    }

    @Transactional
    public ApiResponse<String> registerClinicAdmin(SignUpClinicAdminRequest signUpClinicAdminRequest) {
        Optional<User> existingUser = userRepository.findByEmail(signUpClinicAdminRequest.getEmail());
        Optional<Clinic> existingClinic = clinicRepository.findByEmail(
                signUpClinicAdminRequest.getBusinessEmail());

        boolean createsNewPair = existingUser.isEmpty() && existingClinic.isEmpty();
        boolean resumesPendingPair = existingUser.isPresent()
                && existingClinic.isPresent()
                && isResumableClinicAdminRegistration(
                        existingUser.get(), existingClinic.get())
                && userApprovalService.hasMatchingPendingApprovalRequest(
                        existingUser.get().getId(),
                        Role.CLINIC_ADMIN,
                        existingClinic.get().getId());
        if (!createsNewPair && !resumesPendingPair) {
            return ApiResponse.error("Unable to register clinic administrator");
        }
        if (resumesPendingPair) {
            return ApiResponse.successMessage(
                    "Clinic admin registered successfully, waiting for email verification and system admin approval");
        }

        String verificationCode = generateVerificationCode();
        LocalDateTime codeExpiry = LocalDateTime.now().plusMinutes(codeExpiryMinutes);
        User clinicAdmin = User.builder()
                .firstName(signUpClinicAdminRequest.getFirstName())
                .lastName(signUpClinicAdminRequest.getLastName())
                .email(signUpClinicAdminRequest.getEmail())
                .password(passwordEncoder.encode(signUpClinicAdminRequest.getPassword()))
                .roles(new HashSet<>(Collections.singleton(Role.CLINIC_ADMIN)))
                .provider(AuthProvider.LOCAL)
                .emailVerified(false)
                .verificationCode(hashVerificationCode(verificationCode))
                .verificationCodeExpiry(codeExpiry)
                .approvalStatus(User.ApprovalStatus.PENDING)
                .enabled(false)
                .accountNonExpired(true)
                .credentialsNonExpired(true)
                .accountNonLocked(true)
                .build();
        clinicAdmin = userRepository.save(clinicAdmin);

        Clinic clinic = Clinic.builder()
                .name(signUpClinicAdminRequest.getClinicName())
                .admin(clinicAdmin)
                .address(signUpClinicAdminRequest.getAddress())
                .city(signUpClinicAdminRequest.getCity())
                .state(signUpClinicAdminRequest.getState())
                .zipCode(signUpClinicAdminRequest.getZipCode())
                .country(signUpClinicAdminRequest.getCountry())
                .phoneNumber(signUpClinicAdminRequest.getPhoneNumber())
                .email(signUpClinicAdminRequest.getBusinessEmail())
                .website(signUpClinicAdminRequest.getWebsite())
                .enabled(false)
                .approved(false)
                .approvalBy(null)
                .approvalDate(null)
                .build();
        clinic = clinicRepository.save(clinic);

        clinicAdmin.setClinicId(clinic.getId());
        clinicAdmin.setClinicName(clinic.getName());
        clinicAdmin.setUpdatedAt(LocalDateTime.now());
        clinicAdmin = userRepository.save(clinicAdmin);

        ApiResponse<ApprovalRequestResponse> approvalResponse = userApprovalService.createApprovalRequest(
                clinicAdmin.getId(),
                "Clinic admin sign up for " + clinic.getName());
        if (!approvalResponse.isSuccess()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ApiResponse.error("Unable to register clinic administrator");
        }

        auditEventPublisher.publish(
                "USER_REGISTERED",
                "user:" + clinicAdmin.getId(),
                clinicAdmin.getId(),
                clinic.getId(),
                Map.of("role", Role.CLINIC_ADMIN.name(),
                        "provider", clinicAdmin.getProvider().name()));

        notificationServiceClient.sendVerificationEmail(
                new VerificationEmailRequest(clinicAdmin.getEmail(), verificationCode, "code"));

        return ApiResponse.successMessage(
                "Clinic admin registered successfully, waiting for email verification and system admin approval");
    }

    @Transactional
    public ApiResponse<String> resendVerificationCode(String email) {
        Optional<User> existingUser = userRepository.findByEmail(email);
        if (!existingUser.isPresent()) {
            return ApiResponse.error("User not found");
        }
        User user = existingUser.get();
        if (user.isEmailVerified()) {
            return ApiResponse.error("Email already verified");
        }
        if (user.getVerificationCodeExpiry().isAfter(LocalDateTime.now())) {
            return ApiResponse.successMessage("Verification code already sent");
        }
        String verificationCode = generateVerificationCode();
        LocalDateTime codeExpiry = LocalDateTime.now().plusMinutes(codeExpiryMinutes);
        user.setVerificationCode(hashVerificationCode(verificationCode));
        user.setVerificationCodeExpiry(codeExpiry);
        userRepository.save(user);
        notificationServiceClient.sendVerificationEmail(
                new VerificationEmailRequest(user.getEmail(), verificationCode, "code"));
        return ApiResponse.successMessage("Verification code sent successfully");
    }

    private boolean isActiveApprovedClinic(Clinic clinic) {
        User clinicAdmin = clinic.getAdmin();
        return clinic.getId() != null
                && Boolean.TRUE.equals(clinic.getEnabled())
                && Boolean.TRUE.equals(clinic.getApproved())
                && clinicAdmin != null
                && clinicAdmin.getId() != null
                && clinicAdmin.isEnabled()
                && clinicAdmin.isEmailVerified()
                && clinicAdmin.getProvider() == AuthProvider.LOCAL
                && clinicAdmin.getProviderId() == null
                && clinicAdmin.getApprovalStatus() == User.ApprovalStatus.APPROVED
                && clinicAdmin.getApprovedBy() != null
                && !clinicAdmin.getApprovedBy().isBlank()
                && clinicAdmin.getApprovalDate() != null
                && clinicAdmin.getApprovalRejectionReason() == null
                && hasOnlyRole(clinicAdmin, Role.CLINIC_ADMIN)
                && clinicAdmin.isAccountNonExpired()
                && clinicAdmin.isCredentialsNonExpired()
                && clinicAdmin.isAccountNonLocked()
                && Objects.equals(clinicAdmin.getClinicId(), clinic.getId());
    }

    private boolean isResumableStaffRegistration(User user, Role staffRole, Clinic clinic) {
        return user.getId() != null
                && !user.isEnabled()
                && !user.isEmailVerified()
                && user.getProvider() == AuthProvider.LOCAL
                && user.getProviderId() == null
                && user.getRoles() != null
                && user.getRoles().size() == 1
                && user.getRoles().contains(staffRole)
                && Objects.equals(user.getClinicId(), clinic.getId())
                && user.getApprovalStatus() == User.ApprovalStatus.PENDING
                && user.getApprovedBy() == null
                && user.getApprovalDate() == null
                && user.getApprovalRejectionReason() == null
                && user.isAccountNonExpired()
                && user.isCredentialsNonExpired()
                && user.isAccountNonLocked();
    }

    private boolean isResumableClinicAdminRegistration(User user, Clinic clinic) {
        User currentClinicAdmin = clinic.getAdmin();
        return user.getId() != null
                && clinic.getId() != null
                && !user.isEnabled()
                && !user.isEmailVerified()
                && user.getProvider() == AuthProvider.LOCAL
                && user.getProviderId() == null
                && user.getRoles() != null
                && user.getRoles().size() == 1
                && user.getRoles().contains(Role.CLINIC_ADMIN)
                && Objects.equals(user.getClinicId(), clinic.getId())
                && user.getApprovalStatus() == User.ApprovalStatus.PENDING
                && user.getApprovedBy() == null
                && user.getApprovalDate() == null
                && user.getApprovalRejectionReason() == null
                && user.isAccountNonExpired()
                && user.isCredentialsNonExpired()
                && user.isAccountNonLocked()
                && Boolean.FALSE.equals(clinic.getEnabled())
                && Boolean.FALSE.equals(clinic.getApproved())
                && clinic.getApprovalBy() == null
                && clinic.getApprovalDate() == null
                && currentClinicAdmin != null
                && Objects.equals(currentClinicAdmin.getId(), user.getId());
    }

    private boolean isResumablePatientRegistration(User user) {
        return isVerifiableLocalAccount(user)
                && hasOnlyRole(user, Role.PATIENT)
                && user.getClinicId() == null
                && user.getApprovalStatus() == null
                && user.getApprovedBy() == null
                && user.getApprovalDate() == null
                && user.getApprovalRejectionReason() == null;
    }

    private boolean isPendingStaffVerification(User user) {
        Role staffRole = onlyStaffRole(user);
        if (staffRole == null
                || !isVerifiableLocalAccount(user)
                || !hasPendingApprovalState(user)
                || user.getClinicId() == null
                || !userApprovalService.hasMatchingPendingApprovalRequest(
                        user.getId(), staffRole, user.getClinicId())) {
            return false;
        }
        return clinicRepository.findById(user.getClinicId())
                .filter(this::isActiveApprovedClinic)
                .isPresent();
    }

    private Clinic findPendingClinicAdminVerification(User user) {
        if (!isVerifiableLocalAccount(user)
                || !hasOnlyRole(user, Role.CLINIC_ADMIN)
                || !hasPendingApprovalState(user)
                || user.getClinicId() == null
                || !userApprovalService.hasMatchingPendingApprovalRequest(
                        user.getId(), Role.CLINIC_ADMIN, user.getClinicId())) {
            return null;
        }
        return clinicRepository.findById(user.getClinicId())
                .filter(clinic -> isOwnedPendingClinic(clinic, user))
                .orElse(null);
    }

    private boolean isOwnedPendingClinic(Clinic clinic, User user) {
        return Boolean.FALSE.equals(clinic.getEnabled())
                && Boolean.FALSE.equals(clinic.getApproved())
                && clinic.getApprovalBy() == null
                && clinic.getApprovalDate() == null
                && clinic.getAdmin() != null
                && Objects.equals(clinic.getAdmin().getId(), user.getId());
    }

    private boolean isVerifiableLocalAccount(User user) {
        return user.getId() != null
                && !user.isEnabled()
                && !user.isEmailVerified()
                && user.getProvider() == AuthProvider.LOCAL
                && user.getProviderId() == null
                && user.getPassword() != null
                && !user.getPassword().isBlank()
                && user.isAccountNonExpired()
                && user.isCredentialsNonExpired()
                && user.isAccountNonLocked();
    }

    private boolean hasPendingApprovalState(User user) {
        return user.getApprovalStatus() == User.ApprovalStatus.PENDING
                && user.getApprovedBy() == null
                && user.getApprovalDate() == null
                && user.getApprovalRejectionReason() == null;
    }

    private boolean hasOnlyRole(User user, Role role) {
        return user.getRoles() != null
                && user.getRoles().size() == 1
                && user.getRoles().contains(role);
    }

    private Role onlyStaffRole(User user) {
        if (hasOnlyRole(user, Role.DENTIST)) {
            return Role.DENTIST;
        }
        if (hasOnlyRole(user, Role.RECEPTIONIST)) {
            return Role.RECEPTIONIST;
        }
        return null;
    }

    private String generateVerificationCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private String hashVerificationCode(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((verificationCodePepper + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash verification code", ex);
        }
    }

    private boolean verificationCodeMatches(String rawCode, String storedHash) {
        if (rawCode == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hashVerificationCode(rawCode).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ApiResponse<String> verifyEmailByCode(
            String email,
            String code,
            String newPassword) {
        if (newPassword == null || !STRONG_PASSWORD.matcher(newPassword).matches()) {
            return ApiResponse.error("Unable to verify code");
        }

        Optional<User> existingUser = userRepository.findByEmail(email);

        if (!existingUser.isPresent()) {
            return ApiResponse.error("Unable to verify code");
        }

        User user = existingUser.get();

        if (user.isEmailVerified()) {
            return ApiResponse.successMessage("Verification processed");
        }

        if (!verificationCodeMatches(code, user.getVerificationCode())) {
            return ApiResponse.error("Unable to verify code");
        }

        LocalDateTime now = LocalDateTime.now();
        if (user.getVerificationCodeExpiry() == null || user.getVerificationCodeExpiry().isBefore(now)) {
            return ApiResponse.error("Unable to verify code");
        }

        boolean patientRegistration = isResumablePatientRegistration(user);
        boolean pendingStaffRegistration = isPendingStaffVerification(user);
        Clinic pendingClinicAdminClinic = findPendingClinicAdminVerification(user);
        if (!patientRegistration
                && !pendingStaffRegistration
                && pendingClinicAdminClinic == null) {
            return ApiResponse.error("Unable to verify code");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setEmailVerified(true);
        user.setEnabled(patientRegistration);
        user.setVerificationCode(null);
        user.setVerificationCodeExpiry(null);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationTokenExpiry(null);
        user.setUpdatedAt(LocalDateTime.now());
        authSessionService.publishSecurityChangeAndRevokeAll(user);
        userRepository.save(user);

        return ApiResponse.successMessage("Email verified successfully");
    }

    public ApiResponse<UserResponse> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return ApiResponse.error("User not authenticated");
        }
        UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();
        User user = userRepository.findByEmail(userPrincipal.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));
        return ApiResponse.success(user.toUserResponse());
    }



    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ApiResponse<String> changePassword(ChangePasswordRequest changePasswordRequest) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return ApiResponse.error("User not authenticated");
        }
        UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();
        User user = userRepository.findByEmail(userPrincipal.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setPassword(passwordEncoder.encode(changePasswordRequest.getNewPassword()));
        user.setUpdatedAt(LocalDateTime.now());
        authSessionService.publishSecurityChangeAndRevokeAll(user);
        userRepository.save(user);
        return ApiResponse.successMessage("Password changed successfully");
    }
}
