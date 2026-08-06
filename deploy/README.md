# Docker Compose fallback

The primary homelab deployment is the RKE2/Argo CD contract in
[`chart/`](chart/README.md). This Compose project remains a single-host recovery
path and local integration environment. It runs the gateway, 12 core Java
services, PostgreSQL, and MongoDB. The optional `genai-service` is gated behind
the `ai` profile.

Only the API Gateway loopback port is published. Cloudflare Tunnel maps
`api.mizhifei.press` to that port. TLS, WAF, and public ingress belong at
Cloudflare, not inside the Java container. The PWA runs separately on Vercel.

## Host prerequisites

- Linux host with Docker Engine 27+ and Docker Compose v2
- At least 8 CPU cores, 16 GB RAM, and 40 GB free disk for the full stack
- Cloudflare Tunnel targeting `http://127.0.0.1:8080`
- A dedicated GitHub Actions runner account with Docker access

## First deploy

1. Copy `.env.example` to `.env`.
2. Replace every placeholder and keep `API_BIND_ADDRESS=127.0.0.1` when using a
   Cloudflare Tunnel on the same host.
3. Generate the JWT keys:

   ```bash
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out jwt-private.pem
   openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
   openssl pkcs8 -topk8 -nocrypt -in jwt-private.pem -outform DER | base64 | tr -d '\n'
   openssl pkey -pubin -in jwt-public.pem -outform DER | base64 | tr -d '\n'
   ```

   Put the two single-line outputs in `JWT_RSA_PRIVATE_KEY` and
   `JWT_RSA_PUBLIC_KEY`. Remove the temporary PEM files after storing the keys
   in the homelab secret manager.

4. Set the per-service database credentials in `.env` (every
   `*_SERVICE_DB_PASSWORD` and `*_SERVICE_MONGO_PASSWORD` value — see
   `.env.example`). Application services authenticate with these
   least-privilege credentials (DATA-02); the postgres/mongo root users are
   bootstrap, Flyway-owner, and backup only. Provision the roles before
   starting the apps (both runs are idempotent — re-run after any password
   rotation):

   ```bash
   docker compose up -d postgres mongo redis
   set -a; . ./.env; set +a
   docker compose exec -T \
     -e PGPASSWORD="$POSTGRES_PASSWORD" \
     -e AUTH_SERVICE_DB_PASSWORD -e CLINIC_SERVICE_DB_PASSWORD \
     -e USER_PROFILE_SERVICE_DB_PASSWORD -e APPOINTMENT_SERVICE_DB_PASSWORD \
     -e CLINICAL_RECORDS_SERVICE_DB_PASSWORD -e NOTIFICATION_SERVICE_DB_PASSWORD \
     -e SYSTEM_SERVICE_DB_PASSWORD \
     postgres bash -s < scripts/provision-db-roles.sh
   docker compose exec -T \
     -e MONGO_ADMIN_URI="mongodb://dentistdss:${MONGO_INITDB_ROOT_PASSWORD}@localhost:27017/admin" \
     -e AUDIT_SERVICE_MONGO_PASSWORD -e GENAI_SERVICE_MONGO_PASSWORD \
     -e CLINICAL_RECORDS_SERVICE_MONGO_PASSWORD \
     mongo bash -s < scripts/provision-db-roles.sh
   ```

   Schema migrations need no manual step: the one-shot `migrator` service
   (auth-service image running the db-migrations `Migrator` main class)
   applies them as the migration owner, and every JDBC service waits on its
   successful completion before starting
   (`depends_on: service_completed_successfully`).

5. Authenticate Docker to GHCR if the packages are private.
6. Run `./scripts/deploy.sh`.
7. Configure Cloudflare Tunnel and test the public API URL.
8. Run `./scripts/backup.sh`, copy the backup off-host, and verify a restore
   before treating the deployment as durable.

## CI/CD contract

Compose is not run by GitHub Actions. GitHub-hosted runners publish images and
update the machine-owned `gitops-dev` or `gitops-prod` branch. Argo CD deploys
the Helm chart to the corresponding RKE2 cluster. The protected
`homelab-production` environment gates production promotion.

## Database schema migrations (Flyway)

The PostgreSQL schema is owned by Flyway. The audited baseline
(`db-migrations/src/main/resources/db/migration/V1__baseline.sql`, in the
backend's `db-migrations` module) is on the classpath of every JDBC service,
so each service applies migrations at boot. PostgreSQL advisory locking
serializes concurrent first boots: the first service applies the migration,
the rest wait and no-op.

- **Existing databases** — every service runs `baseline-on-migrate: true`
  with `baseline-version: '1'`. A database without `flyway_schema_history`
  (any database created by the old `ddl-auto: update`) is baselined at
  version 1: V1 is skipped and the existing schema is left untouched.
- **Fresh databases** — V1 is applied in full, including the four native
  enum types that `ddl-auto: update` could never create.
- **Validation** — every JDBC service runs
  `spring.jpa.hibernate.ddl-auto: validate` and fails fast at boot if the
  entities and the migrated schema ever drift. No deployment manifest
  overrides this anymore.
- **Per-service roles (DATA-02)** — `V3__service_roles.sql` creates one
  least-privilege PostgreSQL role per JDBC service (never with a password;
  `scripts/provision-db-roles.sh` sets passwords out-of-band). Application
  datasources connect as these roles (`DB_USERNAME`/`DB_PASSWORD`). The
  one-shot `migrator` compose service runs Flyway as the migration owner,
  so owner credentials never enter application pods
  (`spring.flyway.enabled: false` in the docker/prod profiles; local dev
  still migrates on boot). Every future table-creating migration must also
  grant the new table to its owning service role — new tables default to no
  service access.

Schema changes are new `V2__<description>.sql` migrations added to
`db-migrations/src/main/resources/db/migration/`. **Never edit
`V1__baseline.sql` after it ships** — checksums of applied migrations must
not change; rollback of a bad migration is a new forward migration.

## Database engine upgrades

Compose pins PostgreSQL 18, MongoDB 8.0 (LTS), and Redis 8.10. On an
EXISTING compose volume, PostgreSQL 17 → 18 refuses to boot (PG_VERSION);
upgrade with dump/restore:

```bash
docker compose exec postgres pg_dump -U dentistdss dentistdss > pg17-dump.sql
docker compose down
docker volume rm dentistdss_postgres_data        # only after the dump is verified
docker compose up -d postgres mongo redis         # PG18 initializes fresh
docker compose exec -T postgres psql -U dentistdss dentistdss < pg17-dump.sql
# then the provision script (roles) + migrator one-shot as in First deploy
```

MongoDB 7.0 → 8.0 keeps the data directory; run
`setFeatureCompatibilityVersion "7.0"` before the swap and `"8.0"` after
(see chart/README.md for the exact commands). Redis is a drop-in upgrade.

The schema contract is gated by opt-in `*SchemaContractTest` suites in each
JDBC service: they migrate a real PostgreSQL database and validate the
service's entity mappings against it. They run in CI (postgres service
container) and locally when `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME`,
and `TEST_DATABASE_PASSWORD` are set, e.g. against
`docker run -e POSTGRES_DB=dentistdss -e POSTGRES_USER=dentistdss -e POSTGRES_PASSWORD=dentistdss -p 5432:5432 postgres:18-alpine`.
Still exercise a restore plus one forward-migration cycle on a scratch copy
before storing real patient data in a rebuilt database.

The legacy `docker-entrypoint-initdb.d/01-init.sql` file is DBML
documentation, not executable PostgreSQL. It is not mounted anywhere and
must never be mounted on a fresh database volume.
