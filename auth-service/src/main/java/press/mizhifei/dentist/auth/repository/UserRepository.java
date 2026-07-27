package press.mizhifei.dentist.auth.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import press.mizhifei.dentist.auth.model.User;
import press.mizhifei.dentist.auth.model.AuthProvider;
import press.mizhifei.dentist.auth.model.Role;

import java.util.List;
import java.util.Optional;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    Boolean existsByEmail(String email);

    Optional<User> findByEmailVerificationToken(String token);

    Optional<User> findByProviderIdAndProvider(String providerId, AuthProvider provider);

    @Query("SELECT u FROM User u JOIN u.roles r WHERE r = :role")
    List<User> findByRoles(@Param("role") Role role);

    @Query("SELECT u FROM User u JOIN u.roles r WHERE u.clinicId = :clinicId AND r = :role")
    List<User> findByClinicIdAndRoles(@Param("clinicId") Long clinicId, @Param("role") Role role);

    @Query("SELECT u FROM User u WHERE u.clinicId = :clinicId")
    List<User> findByClinicId(@Param("clinicId") Long clinicId);
}
