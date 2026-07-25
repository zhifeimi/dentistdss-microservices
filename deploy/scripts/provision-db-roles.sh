#!/usr/bin/env bash
# provision-db-roles.sh — DATA-02 operator tool: per-service database roles
# and passwords for PostgreSQL and MongoDB.
#
# Idempotent: safe to run repeatedly and before the Flyway V3 migration has
# ever run (it creates PG roles if absent; V3 reconciles the same roles and
# owns the GRANTs — see db-migrations .../V3__service_roles.sql).
#
# Passwords arrive ONLY through environment variables — never command-line
# arguments or files — and are never echoed. Per-service passwords that are
# unset are skipped, so partial runs are fine.
#
# PostgreSQL (roles: svc_auth, svc_clinic, svc_user_profile, svc_appointment,
#   svc_clinical_records, svc_notification, svc_system):
#   PGHOST (default localhost) PGPORT (5432) PGUSER (dentistdss) PGPASSWORD
#   AUTH_SERVICE_DB_PASSWORD / CLINIC_SERVICE_DB_PASSWORD /
#   USER_PROFILE_SERVICE_DB_PASSWORD / APPOINTMENT_SERVICE_DB_PASSWORD /
#   CLINICAL_RECORDS_SERVICE_DB_PASSWORD / NOTIFICATION_SERVICE_DB_PASSWORD /
#   SYSTEM_SERVICE_DB_PASSWORD
#
# MongoDB (users: svc_audit + svc_genai with collection-scoped custom roles
#   on db dentistdss; svc_clinical_records with readWrite on dentistdss_files):
#   MONGO_ADMIN_URI   e.g. mongodb://dentistdss:<root>@mongo:27017/admin
#   AUDIT_SERVICE_MONGO_PASSWORD / GENAI_SERVICE_MONGO_PASSWORD /
#   CLINICAL_RECORDS_SERVICE_MONGO_PASSWORD
#
# Examples:
#   kubectl exec -i statefulset/postgres -- env PGPASSWORD="$PGPASSWORD" \
#     PGUSER=dentistdss AUTH_SERVICE_DB_PASSWORD=... bash -s \
#     < deploy/scripts/provision-db-roles.sh                    # PG only, in-pod
#   kubectl exec -i statefulset/mongo -- env \
#     MONGO_ADMIN_URI="mongodb://dentistdss:${MONGO_ROOT}@localhost:27017/admin" \
#     AUDIT_SERVICE_MONGO_PASSWORD=... bash -s \
#     < deploy/scripts/provision-db-roles.sh                    # mongo, in-pod
#   (compose) docker compose exec -T postgres bash -s \
#     < deploy/scripts/provision-db-roles.sh                    # see deploy/README.md
set -Eeuo pipefail

log() { printf 'provision-db-roles: %s\n' "$*"; }

# Connection defaults (overridable via the standard libpq environment).
export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${PGUSER:-dentistdss}"

# --------------------------------------------------------------------------
# PostgreSQL: create role if absent, then set its password. Role names are
# fixed literals (never interpolated from env); passwords travel via the
# psql variable :'pw' so they never appear in ps output or server logs
# beyond the one ALTER ROLE statement the superuser runs anyway.
# --------------------------------------------------------------------------
provision_pg_role() {
  local role="$1" pw_var="$2"
  local pw="${!pw_var:-}"
  if [ -z "$pw" ]; then
    log "postgres: skipping ${role} (${pw_var} unset)"
    return 0
  fi
  # psql substitutes :'role' / :'pw' as quoted literals and :"role" as a
  # quoted identifier before sending, so neither value reaches argv.
  psql -v ON_ERROR_STOP=1 --no-psqlrc --quiet -v role="$role" -v pw="$pw" <<'SQL'
do $$
begin
    if not exists (select from pg_roles where rolname = :'role') then
        execute format('create role %I login', :'role');
    end if;
end $$;
alter role :"role" with login password :'pw';
SQL
  log "postgres: role ${role} present, password set"
}

provision_postgres() {
  local any=0 v
  for v in AUTH_SERVICE_DB_PASSWORD CLINIC_SERVICE_DB_PASSWORD \
      USER_PROFILE_SERVICE_DB_PASSWORD APPOINTMENT_SERVICE_DB_PASSWORD \
      CLINICAL_RECORDS_SERVICE_DB_PASSWORD NOTIFICATION_SERVICE_DB_PASSWORD \
      SYSTEM_SERVICE_DB_PASSWORD; do
    [ -n "${!v:-}" ] && any=1
  done
  if [ "$any" = 0 ]; then
    log "postgres: no service passwords set — skipping"
    return 0
  fi
  command -v psql >/dev/null 2>&1 || {
    log "ERROR: psql not found (install postgresql client or run inside the postgres container)"
    return 1
  }
  : "${PGPASSWORD:?PGPASSWORD is required for PostgreSQL provisioning}"
  provision_pg_role svc_auth AUTH_SERVICE_DB_PASSWORD
  provision_pg_role svc_clinic CLINIC_SERVICE_DB_PASSWORD
  provision_pg_role svc_user_profile USER_PROFILE_SERVICE_DB_PASSWORD
  provision_pg_role svc_appointment APPOINTMENT_SERVICE_DB_PASSWORD
  provision_pg_role svc_clinical_records CLINICAL_RECORDS_SERVICE_DB_PASSWORD
  provision_pg_role svc_notification NOTIFICATION_SERVICE_DB_PASSWORD
  provision_pg_role svc_system SYSTEM_SERVICE_DB_PASSWORD
}

# --------------------------------------------------------------------------
# MongoDB: collection-scoped custom roles on db dentistdss (the audit-store
# split — svc_genai can no longer touch audit_seals and vice versa), plus the
# sole-tenant readWrite user for dentistdss_files (GridFS). Passwords are
# read from the mongosh process environment, never from argv or files.
# --------------------------------------------------------------------------
provision_mongo() {
  if [ -z "${MONGO_ADMIN_URI:-}" ]; then
    log "mongo: MONGO_ADMIN_URI unset — skipping"
    return 0
  fi
  command -v mongosh >/dev/null 2>&1 || {
    log "ERROR: mongosh not found (install it or run inside the mongo container)"
    return 1
  }
  mongosh "$MONGO_ADMIN_URI" --quiet <<'JS'
const rw = ['find', 'insert', 'update', 'remove'];
const admin = db.getSiblingDB('admin');
const auditColls = ['audit_entries', 'audit_seals'];
const genaiColls = ['conversations', 'prompt_templates', 'ai_interactions'];
const scopedRole = (name, colls) => ({
  role: name,
  privileges: colls.map((c) => ({ resource: { db: 'dentistdss', collection: c }, actions: rw })),
  roles: [],
});
const customRoles = [scopedRole('audit_store_rw', auditColls), scopedRole('genai_store_rw', genaiColls)];
for (const r of customRoles) {
  const existing = admin.getRole(r.role, { showPrivileges: false });
  if (existing === null) {
    admin.createRole(r);
    print(`mongo: created role ${r.role}`);
  } else {
    admin.updateRole(r.role, { privileges: r.privileges, roles: r.roles });
    print(`mongo: updated role ${r.role}`);
  }
}
const users = [
  { user: 'svc_audit', env: 'AUDIT_SERVICE_MONGO_PASSWORD',
    roles: [{ role: 'audit_store_rw', db: 'admin' }] },
  { user: 'svc_genai', env: 'GENAI_SERVICE_MONGO_PASSWORD',
    roles: [{ role: 'genai_store_rw', db: 'admin' }] },
  { user: 'svc_clinical_records', env: 'CLINICAL_RECORDS_SERVICE_MONGO_PASSWORD',
    roles: [{ role: 'readWrite', db: 'dentistdss_files' }] },
];
for (const u of users) {
  const pw = process.env[u.env] || '';
  if (pw === '') {
    print(`mongo: skipping ${u.user} (${u.env} unset)`);
    continue;
  }
  if (admin.getUser(u.user) === null) {
    admin.createUser({ user: u.user, pwd: pw, roles: u.roles });
    print(`mongo: created user ${u.user}`);
  } else {
    admin.updateUser(u.user, { pwd: pw, roles: u.roles });
    print(`mongo: updated user ${u.user}`);
  }
}
JS
}

provision_postgres
provision_mongo
log "done"
