package press.mizhifei.dentist.clinic.dto;

import java.util.List;

/**
 * One clinic in the public directory ({@code GET /api/public/clinics}).
 * Public fields only: never staff, patients, email or internal IDs.
 * Timestamps are ISO-8601 UTC strings with second precision.
 */
public record PublicClinicResponse(
        String slug,
        List<String> previousSlugs,
        String name,
        String description,
        Address address,
        Location location,
        String phone,
        String website,
        List<OperatingHoursDto> operatingHours,
        List<String> services,
        boolean acceptsOnlineBooking,
        String updatedAt) {

    /** {@code state} is an Australian state or territory code; {@code country} is {@code AU}. */
    public record Address(String street, String suburb, String state, String postcode, String country) {
    }

    public record Location(double latitude, double longitude) {
    }
}
