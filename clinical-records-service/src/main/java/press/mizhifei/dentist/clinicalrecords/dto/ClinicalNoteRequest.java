package press.mizhifei.dentist.clinicalrecords.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ClinicalNoteRequest {

    private Long appointmentId;

    private Long patientId;

    private Long dentistId;

    private Long clinicId;

    private Long visitId;

    @Size(max = 1000, message = "Chief complaint must not exceed 1000 characters")
    private String chiefComplaint;

    private String examinationFindings;

    private String diagnosis;

    private String treatmentPerformed;

    private String treatmentPlan;

    private String prescriptions;

    private String followUpInstructions;

    private String aiAssistedNotes;

    private String[] attachments;

    private String category;

    @Builder.Default
    private Boolean isDraft = false;

    private Long parentNoteId;
}
