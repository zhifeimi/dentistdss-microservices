package press.mizhifei.dentist.auth.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.auth.client.NotificationServiceClient;
import press.mizhifei.dentist.auth.dto.ApiResponse;
import press.mizhifei.dentist.auth.dto.ChangePasswordRequest;
import press.mizhifei.dentist.auth.model.Role;
import press.mizhifei.dentist.auth.model.User;
import press.mizhifei.dentist.auth.repository.ClinicRepository;
import press.mizhifei.dentist.auth.repository.UserRepository;
import press.mizhifei.dentist.auth.security.UserPrincipal;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServicePasswordTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuthSessionService authSessionService;
    private AuthService service;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authSessionService = mock(AuthSessionService.class);
        service = new AuthService(
                userRepository,
                mock(ClinicRepository.class),
                passwordEncoder,
                mock(NotificationServiceClient.class),
                mock(UserApprovalService.class),
                authSessionService);

        user = User.builder()
                .id(42L)
                .email("patient@example.com")
                .password("old-password-hash")
                .firstName("Test")
                .lastName("Patient")
                .roles(Set.of(Role.PATIENT))
                .enabled(true)
                .accountNonExpired(true)
                .credentialsNonExpired(true)
                .accountNonLocked(true)
                .build();
        UserPrincipal principal = UserPrincipal.create(user);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        principal.getAuthorities()));
        when(userRepository.findByEmail("patient@example.com"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.encode("new-password"))
                .thenReturn("new-password-hash");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void passwordChangeRevokesSessionsInsideTransactionalServiceBoundary() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setNewPassword("new-password");

        ApiResponse<String> response = service.changePassword(request);

        assertTrue(response.isSuccess());
        assertTrue("new-password-hash".equals(user.getPassword()));
        var ordered = inOrder(userRepository, authSessionService);
        ordered.verify(authSessionService).publishSecurityChangeAndRevokeAll(user);
        ordered.verify(userRepository).save(user);
        Transactional transactional = AuthService.class
                .getMethod("changePassword", ChangePasswordRequest.class)
                .getAnnotation(Transactional.class);
        assertNotNull(transactional);
        verify(passwordEncoder).encode("new-password");
    }
}
