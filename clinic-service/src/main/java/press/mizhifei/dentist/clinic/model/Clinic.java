package press.mizhifei.dentist.clinic.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "clinics")
public class Clinic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String name;

    private String address;
    private String city;
    private String state;
    private String zipCode;
    private String country;
    private String phoneNumber;
    private String email;
    private String website;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Stable public URL slug, assigned once the clinic is approved. Previous
     * values live in {@code clinic_slug_history}.
     */
    @Column(length = 160, unique = true)
    private String slug;

    /** "Show my clinic in public search" — public listing is opt-in. */
    @Builder.Default
    @Column(nullable = false)
    private Boolean listedPublicly = false;

    private Double latitude;
    private Double longitude;

    @Builder.Default
    private Boolean enabled = false;
    @Builder.Default
    private Boolean approved = false;
    private Long approvalBy;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    @PreUpdate
    private void trimFields() {
        if (name != null) name = name.trim();
        if (address != null) address = address.trim();
        if (city != null) city = city.trim();
        if (state != null) state = state.trim();
        if (zipCode != null) zipCode = zipCode.trim();
        if (country != null) country = country.trim();
        if (phoneNumber != null) phoneNumber = phoneNumber.trim();
        if (email != null) email = email.trim();
        if (website != null) website = website.trim();
        if (description != null) description = description.trim();
        if (listedPublicly == null) listedPublicly = false;
        updatedAt = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
} 