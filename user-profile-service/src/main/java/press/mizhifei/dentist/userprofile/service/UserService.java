package press.mizhifei.dentist.userprofile.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.userprofile.dto.ApiResponse;
import press.mizhifei.dentist.userprofile.dto.UserResponse;
import press.mizhifei.dentist.userprofile.dto.UserUpdateRequest;
import press.mizhifei.dentist.userprofile.model.User;
import press.mizhifei.dentist.userprofile.model.Role;
import press.mizhifei.dentist.userprofile.repository.UserRepository;

import java.util.List;
import java.util.stream.Collectors;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Service
@RequiredArgsConstructor
public class UserService {
    
    private final UserRepository userRepository;

    public List<UserResponse> listAllUsers() {
        return userRepository.findAll().stream()
                .map(User::toUserResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public String getUserEmail(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with id: " + userId));
        return user.getEmail();
    }
    
    @Transactional(readOnly = true)
    public String getUserFullName(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with id: " + userId));
        return user.getFirstName() + " " + user.getLastName();
    }
    
    @Transactional(readOnly = true)
    public ApiResponse<UserDetailsResponse> getUserDetails(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with id: " + userId));

        UserDetailsResponse details = new UserDetailsResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhone(),
                user.getAddress()
        );

        return ApiResponse.success(details);
    }

    public List<UserResponse> listClinicUsers(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found with email: " + userEmail));
        return userRepository.findByClinicId(user.getClinicId()).stream()
                .map(User::toUserResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found with email: " + email));
    }

    @Transactional
    public ApiResponse<UserResponse> updateUserProfile(Long userId, UserUpdateRequest updateRequest) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with id: " + userId));

        // Update only the allowed fields
        if (updateRequest.getFirstName() != null) {
            user.setFirstName(updateRequest.getFirstName());
        }
        if (updateRequest.getLastName() != null) {
            user.setLastName(updateRequest.getLastName());
        }
        if (updateRequest.getPhone() != null) {
            user.setPhone(updateRequest.getPhone());
        }
        if (updateRequest.getDateOfBirth() != null) {
            user.setDateOfBirth(updateRequest.getDateOfBirth());
        }
        if (updateRequest.getAddress() != null) {
            user.setAddress(updateRequest.getAddress());
        }
        if (updateRequest.getProfilePictureUrl() != null) {
            user.setProfilePictureUrl(updateRequest.getProfilePictureUrl());
        }

        User savedUser = userRepository.save(user);
        return ApiResponse.success(savedUser.toUserResponse());
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getClinicDentists(Long clinicId) {
        return userRepository.findByClinicIdAndRoles(clinicId, Role.DENTIST).stream()
                .map(User::toUserResponse)
                .collect(Collectors.toList());
    }

    /**
     * Immutable user-details API DTO. Serialized by Jackson for the
     * {@code /user/{id}/details} and {@code /user/email/{email}/details}
     * endpoints; record components render the same JSON field names the
     * PWA consumes. {@code roles} is defensively copied at construction.
     */
    public record UserDetailsResponse(
            Long id,
            String email,
            String firstName,
            String lastName,
            String fullName,
            String phone,
            String address,
            java.util.Set<String> roles,
            Long clinicId,
            String clinicName,
            Boolean enabled) {

        public UserDetailsResponse(Long id, String email, String firstName, String lastName,
                String phone, String address) {
            this(id, email, firstName, lastName, firstName + " " + lastName,
                    phone, address, null, null, null, null);
        }

        public UserDetailsResponse(Long id, String email, String firstName, String lastName,
                String phone, String address, java.util.Set<String> roles, Long clinicId,
                String clinicName, Boolean enabled) {
            this(id, email, firstName, lastName, firstName + " " + lastName,
                    phone, address, roles, clinicId, clinicName, enabled);
        }

        public UserDetailsResponse {
            roles = roles == null ? null : java.util.Set.copyOf(roles);
        }
    }
}
