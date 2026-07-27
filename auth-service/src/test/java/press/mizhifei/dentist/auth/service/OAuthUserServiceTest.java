package press.mizhifei.dentist.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import press.mizhifei.dentist.auth.model.AuthProvider;
import press.mizhifei.dentist.auth.model.Role;
import press.mizhifei.dentist.auth.model.User;
import press.mizhifei.dentist.auth.repository.UserRepository;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthUserServiceTest {

    private UserRepository userRepository;
    private AuthSessionService authSessionService;
    private OAuthUserService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        authSessionService = mock(AuthSessionService.class);
        service = new OAuthUserService(userRepository, authSessionService);
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void doesNotOverwriteExistingFederatedProviderIdentity() {
        User microsoftPatient = activePatient(AuthProvider.MICROSOFT);
        microsoftPatient.setProviderId("microsoft-subject");
        when(userRepository.findByProviderIdAndProvider(
                "google-subject",
                AuthProvider.GOOGLE))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("patient@example.com"))
                .thenReturn(Optional.of(microsoftPatient));

        assertThrows(BadCredentialsException.class, () ->
                service.processVerifiedGoogleIdentity(
                        "google-subject",
                        "patient@example.com",
                        "Google",
                        "Patient"));

        assertEquals(AuthProvider.MICROSOFT, microsoftPatient.getProvider());
        assertEquals("microsoft-subject", microsoftPatient.getProviderId());
        verify(userRepository, never()).save(any());
    }

    @Test
    void createsGooglePatientWhenOptionalProfileNamesAreMissing() {
        when(userRepository.findByProviderIdAndProvider(
                "google-subject",
                AuthProvider.GOOGLE))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@example.com"))
                .thenReturn(Optional.empty());

        User created = service.processVerifiedGoogleIdentity(
                "google-subject",
                "new@example.com",
                null,
                " ");

        assertEquals("Google", created.getFirstName());
        assertEquals("User", created.getLastName());
        assertEquals(AuthProvider.GOOGLE, created.getProvider());
        assertEquals("google-subject", created.getProviderId());
        assertEquals(Set.of(Role.PATIENT), created.getRoles());
        assertTrue(created.isEnabled());
    }

    @Test
    void rejectsLockedGoogleAccountBeforeUpdatingLoginState() {
        User lockedPatient = activePatient(AuthProvider.GOOGLE);
        lockedPatient.setProviderId("google-subject");
        lockedPatient.setAccountNonLocked(false);
        when(userRepository.findByProviderIdAndProvider(
                "google-subject",
                AuthProvider.GOOGLE))
                .thenReturn(Optional.of(lockedPatient));

        assertThrows(BadCredentialsException.class, () ->
                service.processVerifiedGoogleIdentity(
                        "google-subject",
                        "patient@example.com",
                        "Updated",
                        "Patient"));

        assertEquals("Patient", lockedPatient.getFirstName());
        verify(userRepository, never()).save(any());
    }

    @Test
    void linksOnlyEligibleLocalPatientIdentity() {
        User localPatient = activePatient(AuthProvider.LOCAL);
        when(userRepository.findByProviderIdAndProvider(
                "google-subject",
                AuthProvider.GOOGLE))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("patient@example.com"))
                .thenReturn(Optional.of(localPatient));

        User linked = service.processVerifiedGoogleIdentity(
                "google-subject",
                "patient@example.com",
                "Patient",
                "Example");

        assertEquals(AuthProvider.GOOGLE, linked.getProvider());
        assertEquals("google-subject", linked.getProviderId());
        assertTrue(linked.isEmailVerified());
        verify(authSessionService).publishSecurityChangeAndRevokeAll(localPatient);
        verify(userRepository).save(localPatient);
    }

    private User activePatient(AuthProvider provider) {
        return User.builder()
                .id(42L)
                .email("patient@example.com")
                .firstName("Patient")
                .lastName("Example")
                .provider(provider)
                .roles(Set.of(Role.PATIENT))
                .enabled(true)
                .accountNonExpired(true)
                .credentialsNonExpired(true)
                .accountNonLocked(true)
                .build();
    }
}
