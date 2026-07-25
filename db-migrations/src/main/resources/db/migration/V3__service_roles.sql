-- ---------------------------------------------------------------------------
-- DATA-02: per-service runtime roles (least privilege)
--
-- Every JDBC service connects at runtime with its own role holding DML only
-- on the tables its JPA entities map (including the shared users / clinics /
-- user_approval_requests tables, which both mapping services write, and the
-- element-collection/join tables user_roles and clinic_admin). Flyway keeps
-- connecting as the migration owner (spring.flyway.user) for DDL; these
-- roles can never CREATE/ALTER/DROP.
--
-- Passwords are NOT set here — a role created without PASSWORD cannot
-- authenticate. deploy/scripts/provision-db-roles.sh sets them out-of-band
-- from Vault/compose secrets (operator runbook in deploy/chart/README.md).
-- The blocks are idempotent so the migration is safe on fresh databases,
-- baseline-adopted databases, and databases where the provision script
-- already created the roles.
--
-- AUTHORING RULE (from V3 onward): every migration that creates a table
-- MUST also GRANT that table to the owning service role(s) here — new
-- tables default to no service access, so a missing grant breaks the owning
-- service at boot (ddl-auto=validate reads only its own mapped tables).
-- ---------------------------------------------------------------------------

do $$
begin
    if not exists (select from pg_roles where rolename = 'svc_auth') then
        create role svc_auth login;
    end if;
    if not exists (select from pg_roles where rolename = 'svc_clinic') then
        create role svc_clinic login;
    end if;
    if not exists (select from pg_roles where rolename = 'svc_user_profile') then
        create role svc_user_profile login;
    end if;
    if not exists (select from pg_roles where rolename = 'svc_appointment') then
        create role svc_appointment login;
    end if;
    if not exists (select from pg_roles where rolename = 'svc_clinical_records') then
        create role svc_clinical_records login;
    end if;
    if not exists (select from pg_roles where rolename = 'svc_notification') then
        create role svc_notification login;
    end if;
    if not exists (select from pg_roles where rolename = 'svc_system') then
        create role svc_system login;
    end if;
end $$;

-- auth-service: users + user_roles (element collection), clinics +
-- clinic_admin (join table), user_approval_requests, own session/outbox
-- tables.
grant select, insert, update, delete on users, user_roles, clinics, clinic_admin,
    user_approval_requests, auth_sessions, auth_audit_outbox, auth_security_outbox
    to svc_auth;
grant usage on sequence user_id_seq to svc_auth;

-- clinic-service: clinics (shared with auth), services.
grant select, insert, update, delete on clinics, services to svc_clinic;

-- user-profile-service: users + user_roles and user_approval_requests
-- (shared with auth), patients, patient_profiles, medical_history.
grant select, insert, update, delete on users, user_roles, user_approval_requests,
    patients, patient_profiles, medical_history to svc_user_profile;
grant usage on sequence user_id_seq to svc_user_profile;
grant usage on sequence patient_record_id_seq to svc_user_profile;

-- appointment-service: appointments, dentist_availability.
grant select, insert, update, delete on appointments, dentist_availability
    to svc_appointment;
grant usage on sequence appointment_id_seq to svc_appointment;

-- clinical-records-service: clinical_notes, dental_images, service_visits,
-- treatment_plans, treatment_plan_items.
grant select, insert, update, delete on clinical_notes, dental_images, service_visits,
    treatment_plans, treatment_plan_items to svc_clinical_records;

-- notification-service: notifications, notification_templates.
grant select, insert, update, delete on notifications, notification_templates
    to svc_notification;
grant usage on sequence notification_id_seq to svc_notification;

-- system-service: system_settings.
grant select, insert, update, delete on system_settings to svc_system;
