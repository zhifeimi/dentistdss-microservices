package press.mizhifei.dentist.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import press.mizhifei.dentist.auth.model.AuthProvider;
import press.mizhifei.dentist.auth.model.Role;
import press.mizhifei.dentist.auth.model.User;
import press.mizhifei.dentist.auth.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthUserService {

    private final UserRepository userRepository;
    private final AuthSessionService authSessionService;

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public User processVerifiedGoogleIdentity(
            String providerId,
            String email,
            String firstName,
            String lastName) {
        User user = userRepository.findByProviderIdAndProvider(providerId, AuthProvider.GOOGLE)
                .orElseGet(() -> linkOrCreateGoogleUser(providerId, email, firstName, lastName));

        if (!isActiveAccount(user)) {
            throw new BadCredentialsException("Account is unavailable");
        }

        if (StringUtils.hasText(firstName)) {
            user.setFirstName(firstName);
        }
        if (StringUtils.hasText(lastName)) {
            user.setLastName(lastName);
        }
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    private User linkOrCreateGoogleUser(String providerId, String email, String firstName, String lastName) {
        return userRepository.findByEmail(email)
                .map(existing -> linkExistingPatient(existing, providerId, firstName, lastName))
                .orElseGet(() -> createGooglePatient(providerId, email, firstName, lastName));
    }

    private User linkExistingPatient(User user, String providerId, String firstName, String lastName) {
        boolean patientOnly = user.getRoles() != null
                && user.getRoles().size() == 1
                && user.getRoles().contains(Role.PATIENT);
        boolean localIdentity = user.getProvider() == AuthProvider.LOCAL
                && !StringUtils.hasText(user.getProviderId());
        if (!patientOnly || !localIdentity || !isActiveAccount(user)) {
            log.warn("Blocked automatic Google account linking for ineligible existing account");
            throw new BadCredentialsException("Existing account requires explicit reauthentication before linking");
        }

        user.setProvider(AuthProvider.GOOGLE);
        user.setProviderId(providerId);
        user.setEmailVerified(true);
        if (!StringUtils.hasText(user.getFirstName())) {
            user.setFirstName(nameOrDefault(firstName, "Google"));
        }
        if (!StringUtils.hasText(user.getLastName())) {
            user.setLastName(nameOrDefault(lastName, "User"));
        }
        authSessionService.publishSecurityChangeAndRevokeAll(user);
        return user;
    }

    private User createGooglePatient(String providerId, String email, String firstName, String lastName) {
        return User.builder()
                .email(email)
                .firstName(nameOrDefault(firstName, "Google"))
                .lastName(nameOrDefault(lastName, "User"))
                .provider(AuthProvider.GOOGLE)
                .providerId(providerId)
                .roles(new HashSet<>(Collections.singleton(Role.PATIENT)))
                .emailVerified(true)
                .enabled(true)
                .accountNonExpired(true)
                .credentialsNonExpired(true)
                .accountNonLocked(true)
                .lastLoginAt(LocalDateTime.now())
                .build();
    }

    private String nameOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private boolean isActiveAccount(User user) {
        return user != null
                && user.isEnabled()
                && user.isAccountNonExpired()
                && user.isCredentialsNonExpired()
                && user.isAccountNonLocked();
    }
}
