package press.mizhifei.dentist.auth.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import press.mizhifei.dentist.auth.dto.UserResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import org.hibernate.annotations.DynamicUpdate;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(columnNames = "email")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@DynamicUpdate
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_sequence")
    @SequenceGenerator(
        name = "user_sequence",
        sequenceName = "user_id_seq",
        allocationSize = 1,
        initialValue = 1000000
    )
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    private String password;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(length = 20)
    private String phone;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(columnDefinition = "TEXT")
    private String address;

    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"), uniqueConstraints = @UniqueConstraint(columnNames = {"user_id"}))
    @Column(name = "role")
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private AuthProvider provider = AuthProvider.LOCAL; // To track OAuth2 provider
    
    @Column(name = "provider_id")
    private String providerId; // User ID from the OAuth2 provider

    @Column(name = "profile_picture_url", length = 500)
    private String profilePictureUrl;

    @Column(name = "email_verified")
    @Builder.Default
    private boolean emailVerified = false;
    
    @Column(name = "email_verification_token")
    private String emailVerificationToken;
    
    @Column(name = "email_verification_token_expiry")
    private LocalDateTime emailVerificationTokenExpiry;

    @Column(name = "phone_verified")
    @Builder.Default
    private Boolean phoneVerified = false;

    @Column(name = "verification_code")
    private String verificationCode;
    
    @Column(name = "verification_code_expiry")
    private LocalDateTime verificationCodeExpiry;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status")
    private ApprovalStatus approvalStatus;
    
    @Column(name = "approval_rejection_reason")
    private String approvalRejectionReason;
    
    @Column(name = "approved_by")
    private String approvedBy;
    
    @Column(name = "approval_date")
    private LocalDateTime approvalDate;

    @Builder.Default
    private boolean enabled = false;

    @Column(name = "security_version", nullable = false, columnDefinition = "BIGINT DEFAULT 1")
    @Builder.Default
    private long securityVersion = 1L;

    @Column(name = "account_non_expired")
    @Builder.Default
    private boolean accountNonExpired = true;
    
    @Column(name = "credentials_non_expired")
    @Builder.Default
    private boolean credentialsNonExpired = true;
    
    @Column(name = "account_non_locked")
    @Builder.Default
    private boolean accountNonLocked = true;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "clinic_id")
    private Long clinicId; // For Dentist, Receptionist, Clinic Admin
    
    @Column(name = "clinic_name")
    private String clinicName; // For Clinic Admin

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
    
    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (securityVersion <= 0) {
            securityVersion = 1L;
        }
        trimFields();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        trimFields();
    }
    
    private void trimFields() {
        if (email != null) email = email.trim();
        if (password != null) password = password.trim();
        if (firstName != null) firstName = firstName.trim();
        if (lastName != null) lastName = lastName.trim();
        if (phone != null) phone = phone.trim();
        if (address != null) address = address.trim();
        if (providerId != null) providerId = providerId.trim();
        if (profilePictureUrl != null) profilePictureUrl = profilePictureUrl.trim();
        if (emailVerificationToken != null) emailVerificationToken = emailVerificationToken.trim();
        if (verificationCode != null) verificationCode = verificationCode.trim();
        if (approvalRejectionReason != null) approvalRejectionReason = approvalRejectionReason.trim();
        if (approvedBy != null) approvedBy = approvedBy.trim();
        if (clinicName != null) clinicName = clinicName.trim();
    }

    // Enum for Approval Status
    public enum ApprovalStatus {
        PENDING,
        APPROVED,
        REJECTED
    }

    public void incrementSecurityVersion() {
        if (securityVersion <= 0 || securityVersion == Long.MAX_VALUE) {
            throw new IllegalStateException("Invalid user security version");
        }
        securityVersion++;
    }

    public UserResponse toUserResponse() {
        return UserResponse.builder()
            .id(id.toString())
            .email(email)
            .firstName(firstName)
            .lastName(lastName)
            .roles(roles)
            .clinicId(clinicId)
            .clinicName(clinicName)
            .enabled(enabled)
            .phone(phone)
            .address(address)
            .profilePictureUrl(profilePictureUrl)
            .build();
    }
}
