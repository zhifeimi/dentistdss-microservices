package press.mizhifei.dentist.clinic.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import press.mizhifei.dentist.clinic.model.Clinic;

import java.time.LocalDateTime;
import java.util.Collection;
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
public interface ClinicRepository extends JpaRepository<Clinic, Long> {
    List<Clinic> findByEnabledTrue();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Clinic c where c.id = :id")
    Optional<Clinic> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT c FROM Clinic c WHERE c.enabled = true " +
            "AND (:keywords IS NULL OR :keywords = '' OR " +
            "LOWER(c.name) LIKE LOWER(CONCAT('%', :keywords, '%')) OR " +
            "LOWER(c.address) LIKE LOWER(CONCAT('%', :keywords, '%')) OR " +
            "LOWER(c.city) LIKE LOWER(CONCAT('%', :keywords, '%')) OR " +
            "LOWER(c.state) LIKE LOWER(CONCAT('%', :keywords, '%')) OR " +
            "LOWER(c.zipCode) LIKE LOWER(CONCAT('%', :keywords, '%')) OR " +
            "LOWER(c.country) LIKE LOWER(CONCAT('%', :keywords, '%')) OR " +
            "LOWER(c.phoneNumber) LIKE LOWER(CONCAT('%', :keywords, '%')) OR " +
            "LOWER(c.email) LIKE LOWER(CONCAT('%', :keywords, '%')))")
    List<Clinic> searchClinics(@Param("keywords") String keywords);

    /**
     * The public directory contract: approved, enabled, opted in, and with a
     * slug. Further eligibility checks (an Australian state, a suburb) happen
     * in {@code PublicClinicDirectoryService}.
     */
    String PUBLICLY_LISTED = "c.approved = true and c.enabled = true "
            + "and c.listedPublicly = true and c.slug is not null";

    @Query("select c from Clinic c where " + PUBLICLY_LISTED)
    List<Clinic> findPubliclyListed();

    @Query("select c from Clinic c where c.slug = :slug and " + PUBLICLY_LISTED)
    Optional<Clinic> findPubliclyListedBySlug(@Param("slug") String slug);

    /** Approved, active clinics still waiting for their public slug. */
    @Query("select c from Clinic c where c.approved = true and c.enabled = true "
            + "and c.slug is null order by c.id")
    List<Clinic> findApprovedWithoutSlug();

    /** Initialize only the owned fields; never flush a stale clinic profile or consent. */
    @Modifying
    @Query("update Clinic c set c.slug = :slug, c.updatedAt = :updatedAt where c.id = :id "
            + "and c.slug is null and c.approved = true and c.enabled = true")
    int assignSlugIfMissing(@Param("id") Long id, @Param("slug") String slug,
            @Param("updatedAt") LocalDateTime updatedAt);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    /**
     * Records a change to data shown on the public clinic page that lives
     * outside the clinics row (services), so {@code updatedAt} stays an
     * honest "last modified" for the sitemap.
     */
    @Modifying
    @Query("update Clinic c set c.updatedAt = :updatedAt where c.id in :ids")
    int touchUpdatedAt(@Param("ids") Collection<Long> ids, @Param("updatedAt") LocalDateTime updatedAt);
} 