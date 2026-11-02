package press.mizhifei.dentist.clinic.dto;

import java.util.List;

/** Body of {@code GET /api/public/clinics}: every listed clinic, sorted by slug. */
public record PublicClinicDirectoryResponse(String generatedAt, List<PublicClinicResponse> clinics) {
}
