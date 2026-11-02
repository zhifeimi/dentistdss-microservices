package press.mizhifei.dentist.clinic.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import press.mizhifei.dentist.clinic.model.PublicDirectorySyncState;

import java.util.Optional;

@Repository
public interface PublicDirectorySyncStateRepository extends JpaRepository<PublicDirectorySyncState, Integer> {

    /**
     * Locks the singleton coordination row for the current transaction, or
     * returns empty when another replica holds it — that replica is already
     * doing this tick's work.
     */
    @Query(value = "select * from public_directory_sync where id = 1 for update skip locked",
            nativeQuery = true)
    Optional<PublicDirectorySyncState> lockSingleton();
}
