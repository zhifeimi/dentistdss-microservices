package press.mizhifei.dentist.userprofile.service;

import org.junit.jupiter.api.Test;
import press.mizhifei.dentist.userprofile.dto.UserResponse;
import press.mizhifei.dentist.userprofile.model.Role;
import press.mizhifei.dentist.userprofile.model.User;
import press.mizhifei.dentist.userprofile.repository.UserRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceClinicStaffTest {

    @Test
    void clinicStaffExcludesPatientsOfTheClinic() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findByClinicId(7L)).thenReturn(List.of(
                user(1L, Role.CLINIC_ADMIN),
                user(2L, Role.DENTIST),
                user(3L, Role.RECEPTIONIST),
                user(4L, Role.PATIENT)));

        List<String> ids = new UserService(repository).getClinicStaff(7L).stream()
                .map(UserResponse::getId)
                .toList();

        assertEquals(List.of("1", "2", "3"), ids);
    }

    private static User user(Long id, Role role) {
        return User.builder().id(id).email(id + "@example.test").clinicId(7L)
                .roles(new HashSet<>(Set.of(role))).build();
    }
}
