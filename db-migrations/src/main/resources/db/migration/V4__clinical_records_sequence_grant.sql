-- ---------------------------------------------------------------------------
-- V3 granted patient_record_id_seq only to svc_user_profile, but
-- clinical-records-service's ClinicalNote draws its ids from it. Without a
-- privilege the sequence is invisible in information_schema.sequences, so
-- ddl-auto=validate fails clinical-records-service boot ("missing sequence").
-- ---------------------------------------------------------------------------

grant usage on sequence patient_record_id_seq to svc_clinical_records;
