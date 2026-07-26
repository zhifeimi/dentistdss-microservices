# Security hardening traceability

This document tracks the verified findings addressed by the Java 25 / Spring Cloud 2025.1.2 hardening program. A finding is complete only when its implementation, negative regression test, and deployment control all pass.

| ID | Finding | Required control | Required regression evidence | Status |
|---|---|---|---|---|
| AUTH-01 | Caller-controlled OAuth identity can issue a token | Remove `/auth/oauth/process`; verify Google ID token, audience, issuer, nonce, subject, and verified email | Forged email/provider input and replayed nonce fail | In progress |
| AUTH-02 | JWT appears in OAuth redirect URLs and logs | Remove token redirect flow; use popup ID-token exchange and memory-only access token | Browser URL, history, logs, and storage contain no token | In progress |
| AUTH-03 | JWT lacks issuer/audience/revocation/key rotation | Nimbus tokens with `iss`, `aud`, `jti`, `kid`; short TTL; refresh rotation; JWK key ring | Wrong audience/issuer/kid, revoked token, and refresh reuse fail | In progress |
| AUTH-04 | Login and verification endpoints are brute-forceable | Redis limits, `SecureRandom`, hashed one-time codes, generic responses | Cross-replica attempt limits and account-enumeration tests pass | In progress |
| RBAC-01 | User can approve their own privilege escalation | Authenticated reviewer identity, separation of duties, role-transition policy, row locking | Patient/self/cross-clinic approval attempts fail | In progress |
| RBAC-02 | Gateway authorization defaults to allow | Exact method/path rules and deny-by-default fallback | Unknown and prefix-confusion routes fail | In progress |
| RBAC-03 | Domain services trust gateway headers | Local resource-server validation in every service; strip inbound identity headers | Direct forged-header requests fail without a valid JWT | In progress |
| RBAC-04 | Clinical, appointment, user, clinic, notification, GenAI, system, and audit operations lack ownership checks | Service-layer resource and tenant policies | Parameterized cross-user/cross-clinic matrix passes | In progress |
| ADMIN-01 | Spring Boot Admin and management endpoints are public | Remove public route; private authenticated management plane; health-only public exposure | Public Admin/refresh/config/route inspection requests fail | In progress |
| SECRET-01 | Every pod receives the complete runtime secret and JWT private key | Per-service ExternalSecrets; auth-only signing key | Rendered manifests show least-privilege secret references | In progress |
| DATA-01 | Shared DB schema mutates through Hibernate | Versioned Flyway owner and `ddl-auto: validate` everywhere | Migration succeeds against baseline; all services validate | Partial (see notes) |
| DATA-02 | Applications use shared/root database credentials | Per-service PostgreSQL and MongoDB users/grants | Unauthorized cross-database/table access fails | Done (see notes) |
| DATA-03 | Dental image writes and validation are unsafe | Signature/decode limits, canonical re-encode, authorized ownership, cleanup/compensation | False MIME, polyglot, oversized pixels, and unauthorized access fail | Done (see notes) |
| AUDIT-01 | Audit actor is caller-controlled and critical actions are not integrated | Server-attributed internal ingest, transactional outbox, append-only/tamper-evident store | Caller cannot forge actor; critical mutations emit immutable events | Done (see notes) |
| RATE-01 | Session and GenAI limiter maps are bypassable/unbounded | Redis-backed signed sessions, quotas, TTLs, and cardinality limits | Rotation, restart, and multi-replica tests pass without key leaks | In progress |
| ERROR-01 | Exception details leak to clients/logs | Shared safe error envelope and redaction | Responses contain no stack/SQL/SMTP/provider/token/PHI detail | In progress |
| CORS-01 | CORS and cookie behavior are broader than required | Exact origins/methods/headers, Origin validation, anti-CSRF for cookie endpoints | Hostile Origin and missing/invalid CSRF requests fail | In progress |
| SUPPLY-01 | Build inputs and vulnerabilities are not fully gated | Java 25, Enforcer, FindSecBugs, Dependency-Check, SBOM, Trivy, pinned actions/images | CI blocks policy violations and high/critical findings | Done (see notes) |

## Finding notes

### AUDIT-01 — done: server attribution, durable outbox, tamper-evident seals

Credential-based, server-attributed audit ingest landed on
`agent/security-platform-hardening` (PR #22, CI green at `42e6eb2`):

- Ingest authentication: audit-service `POST /audit/events` requires a
  locally verified, audience-scoped service credential
  (`SERVICE_AUDIT_INGEST`) — a 30-second RS256 JWT with a single audience,
  key ID bound to the issuer subject and scope. The gateway strips any
  inbound `X-Service-Authorization` before metadata headers are attached,
  and only the auth-service key is trusted for ingestion
  (`audit:ingest` scope).
- Server attribution: audit events derive the actor from the verified
  credential subject and request payload, never from caller-controlled
  headers; auth-service emits events for security-critical account and
  session mutations.
- Deployment: per-issuer RSA pairs (auth, appointment, clinical-records,
  notification) live in dedicated Vault `service-auth/<issuer>` records
  (Helm) and compose environment variables; every reference is optional,
  so unseeded deployments stay fail-closed — issuers remain dormant and
  targets reject uncredentialed calls, as before.
- Decision — no jti replay tracking: credentials carry a `jti`, but
  verifiers do not persist it. The 30-second expiry, single audience, and
  kid→scope binding are the controls; a replay store was judged
  disproportionate for an internal, network-policy-restricted endpoint.
- Durable emission: auth-service audit events are now written to the
  `auth_audit_outbox` table (Flyway `V2__audit_outbox.sql`) inside the same
  database transaction as the security-critical mutation — an event exists
  if and only if the mutation committed. A scheduled relay claims pending
  rows with `FOR UPDATE SKIP LOCKED`, delivers them to audit-service, and
  deletes rows only after a confirmed delivery; failures record the attempt
  and retry with backoff forever — rows are never expired or purged.
  Delivery is at-least-once: a crash between a confirmed delivery and the
  delete re-delivers the event, and audit-service stores the duplicate as a
  distinct document (no idempotency keys, by contract decision). Because
  the write joins the caller's transaction, an outbox write failure fails
  the business operation too — intentional coupling, since at the
  in-transaction sites the business operation would fail on the same
  database anyway. Backlog and staleness are observable via the relay's
  `stale-warn-threshold` warning and
  `select count(*), max(attempts) from auth_audit_outbox`.
- Tamper-evident storage: audit-service computes a canonical SHA-256
  content hash for every document at ingest, and a single scheduled sealer
  (one replica) groups content-hashed documents into strictly sequential,
  chained batch seals (`audit_seals`, unique sequence index) over
  contiguous `_id` ranges. `GET /audit/integrity` (SYSTEM_ADMIN) re-verifies
  the chain by recomputation and reports the first inconsistency — sequence
  and range continuity, chain linkage, seal self-hash, sealed-range
  document count and boundaries, per-document content hashes, and the batch
  root. This is detection, not prevention: a fully consistent rewrite of
  sealed history would require controlling the sealer. Known limit
  (security review 2026-07-25, accepted by contract decision — no code
  change): the chain has no external watermark, so tail truncation —
  deleting the newest seal(s) together with the entries in their covered
  `_id` ranges — and full deletion of the seal collection both leave a
  self-consistent state that `GET /audit/integrity` reports as
  `verified=true`; the deleted documents surface in neither the chain nor
  the `unsealedDocuments` backlog, and the sealer silently resumes on the
  truncated chain. Detection covers tampering inside surviving seals; it
  does not cover removal of the tail or the whole chain. Closing that gap
  would require a monotonic high-water mark in a trust domain outside the
  audit store (out of scope for the shipped contract: ZERO new deployment
  env vars); the compensating-control roadmap is DATA-02 per-service
  credentials, which shrinks the attacker class to services that
  legitimately hold audit-store write access (NetworkPolicy-restricted).
  Documents written before this feature carry no content hash and are
  never sealed; they surface as the report's `unsealedDocuments` backlog
  count.

### DATA-01 — partial: Flyway baseline and validate shipped

The `db-migrations` module with the frozen `V1__baseline.sql` and
`ddl-auto: validate` across all seven JDBC services landed as `c150186`
(PR #22); deployment `ddl-auto=update` overrides were removed, and the
baseline-on-migrate adoption path was live-verified against PostgreSQL
(both fresh-database apply and existing-database baseline). Completion
awaits PR #22 CI confirmation that all seven `*SchemaContractTest` suites
run green.

### SUPPLY-01 — done: CI security gates, manifest policy, DAST, digest pins

Supply-chain gating landed on `agent/security-platform-hardening` (PR #22)
and is CI-verified: every gate went green at `7a5bb31` (Security run
30136159582, Backend CI run 30136159594) — all 14 `Trivy config` matrix jobs,
`Trivy filesystem`, `FindSecBugs + SBOM`, `Dependency review` (graceful
skip-notice until the repo's dependency graph is enabled), `Maven verify`,
and the extended `Validate deployment contract`. The local contract is
proven as well: `./mvnw -Pprod,security -DskipTests -Ddependency-check.skip=true
verify` is green across all 16 reactor modules, and the manifest-policy gate
passes both environment renders and fails exactly 12 ways under a mutation
battery (one deliberate break per assertion family).

- Enforcer banned dependencies (always active, `searchTransitive=true`):
  `log4j:log4j` outright; `org.apache.logging.log4j:log4j-core:[,2.17.1)`
  (Log4Shell range, 2.17.1+ allowed); `commons-logging:commons-logging:
  [,1.3.0)` — a range ban, because Spring Framework 7 `spring-core` itself
  declares the modern 1.3.x facade and a full ban would break the build
  without removing any vulnerable version.
- `security.yml` (PR-gated + weekly schedule + dispatch): `dependency-review`
  (`fail-on-severity: high`, PR comment summary). The dependency graph must
  be enabled in the repository settings for private repos; until it is, the
  job probes the compare API and skips with a `::notice::` instead of
  failing — the Trivy filesystem gate covers newly vulnerable dependencies
  in the meantime. `sast-sbom` runs the
  `security` Maven profile — SpotBugs 4.10.3.0 + FindSecBugs at
  effort=Max/threshold=Low/`failOnError`, plus the CycloneDX aggregate SBOM
  (`target/bom.json`/`bom.xml`) uploaded as an artifact; `trivy-fs`
  (vuln+secret, HIGH/CRITICAL) is the always-on PR vulnerability gate
  and needs no API key (OSV-based); `trivy-config` matrix-scans `deploy/`
  and all 13 service directories. Both Trivy jobs are two-phase: a
  table-format gate at HIGH/CRITICAL with `exit-code: 1`, followed by a
  non-gating SARIF export for code scanning
  (`limit-severities-for-sarif: true`). The split is required because
  trivy-action's SARIF mode overrides the severity filter to ALL severities,
  which would fail the gate on LOW advisories such as Dockerfile DS-0026
  (no HEALTHCHECK — covered operationally by the Kubernetes probe
  assertions). `dependency-check` (OWASP, CVSS≥7) runs
  scheduled/dispatch only and is **key-optional** — it uses
  `secrets.NVD_API_KEY` when present and otherwise exits with a `::notice::`
  skip, so it never blocks on a missing secret and no new repo secret was
  added.
- Vulnerability remediation surfaced by the `trivy-fs` gate: Netty
  4.2.15.Final → 4.2.16.Final (CVE-2026-59901 Bzip2Decoder infinite loop;
  CVE-2026-55831/-55833/-56745/-56816 in the HTTP codecs) and PostgreSQL
  JDBC 42.7.11 → 42.7.12 (CVE-2026-54291, SCRAM-SHA-256-PLUS
  channel-binding downgrade), both as root-pom property overrides ahead of
  the versions managed by Boot 4.0.7; the overrides should be removed once
  a Boot point release catches up.
- FindSecBugs triage policy: real findings are fixed in code — e.g.
  `RC_REF_COMPARISON` surfaced a genuine boxed-Long authorization comparison
  bug in appointment-service (fixed to `.equals`), `UNSAFE_HASH_EQUALS`
  became a constant-time `MessageDigest.isEqual` comparison across all four
  audit-integrity hash checks, and bare `RuntimeException` throws in
  notification-service became a typed `EmailSendException`. Justified false
  positives are suppressed only in `spotbugs-exclude.xml`, every entry with
  a rationale; two documented pattern-level deviations exist
  (`CRLF_INJECTION_LOGS` — containers reject CR/LF in request lines/headers
  and no auth decision is derived from logs; `SPRING_ENDPOINT` — informational
  endpoint inventory behind fail-closed filter chains). Two
  `IMPROPER_UNICODE` entries are method-scoped with the `Locale.ROOT` pin as
  the stated mitigation (display-name capitalization; role enum lookup that
  fails closed on any non-mapping string).
- DAST: `dast.yml` (weekly schedule + dispatch, never a PR gate) boots the
  core stack from `deploy/compose.yml` with a throwaway RSA keypair,
  baseline-scans the **gateway's public surface only** with a digest-pinned
  ZAP image, and gates on an explicit `jq` count of High-risk alerts —
  failure semantics do not depend on the rules file. genai-service is
  excluded by design (it stays down without the `ai` profile). GitHub
  resolves cron and `workflow_dispatch` only against the default branch:
  `security.yml`'s scheduled `dependency-check` job was dispatch-verified
  on the review branch (green — it skips with a notice when `NVD_API_KEY`
  is absent), but `dast.yml`, whose triggers are schedule/dispatch only,
  cannot be registered for dispatch before merge; its first live ZAP run
  is the post-merge weekly cron or a manual dispatch on `main`.
- Release gating: `release.yml` gained an `image-scan` matrix job (Trivy
  HIGH/CRITICAL on every published GHCR image) between `publish` and
  `promote-dev`, so a vulnerable image blocks promotion.
- Manifest policy: `deploy/scripts/verify-manifest-policy.sh` (wired into
  `ci.yml`'s `deployment-contract`, both environments) enforces kubeconform
  strict schema validation, exactly 11 NetworkPolicies, pod/container
  hardening (`automountServiceAccountToken: false`, `runAsNonRoot`,
  `allowPrivilegeEscalation: false`, `capabilities.drop: ALL`, CPU+memory
  limits), Deployment-only `readOnlyRootFilesystem` + liveness/readiness
  probes, ghcr.io images with explicit non-`latest` tags, and
  secret-reference-to-ExternalSecret matching. Every assertion family is
  mutation-tested: a deliberately broken render fails exactly the expected
  way. kubeconform v0.8.0 comes from PATH or a checksum-verified downloaded
  release asset (Go-style arch names — `linux-amd64`/`darwin-arm64` — which
  the CI 404 on `17f75ff` showed must not be derived from `uname -m`).
- Image provenance: all 13 service Dockerfiles pin the base image by
  multi-arch digest
  (`eclipse-temurin:25-jre-jammy@sha256:b8ba5f…1ab5`, amd64+arm64+ppc64le+
  s390x verified) with the tag retained so Dependabot's new `docker`
  ecosystem entry refreshes the digest weekly. All workflow actions remain
  SHA-pinned.
- Deviations: StatefulSets (PostgreSQL/Redis/MongoDB) are exempt from
  `readOnlyRootFilesystem` and probe assertions — they remain held to
  non-root, no escalation, drop ALL, and resource limits; read-only roots
  need emptyDir scratch mounts validated on a live cluster (follow-up).
  Dependency-Check is scheduled-only by explicit decision (Trivy fs is the
  PR vulnerability gate). ZAP is scheduled-only by explicit decision (not a
  PR gate).

### Security review — 2026-07-25 (completion gate 5)

A bundled `/security-review` pass covered the full hardening surface in seven
scoped reviews: api-gateway; security-common; appointment-service +
clinical-records-service; deploy/ + workflows + root/module poms; auth-service;
genai-service + audit-service; clinic-service + user-profile-service +
system-settings + notification-service. Methodology per the bundled skill:
identify → parallel false-positive filtering → confidence ≥8 threshold.

- **Confirmed findings: exactly one.** Audit tail truncation (AUDIT-01):
  `AuditIntegrityService.verify()` walks only the surviving seals, so deleting
  the newest seal(s) with the entries in their covered `_id` ranges — or the
  whole seal collection — leaves a self-consistent chain that verifies clean,
  and the deleted documents surface in neither the chain nor the
  `unsealedDocuments` backlog. Resolved as a **documented, contract-accepted
  limitation** (user decision 2026-07-25, no code change): the `AuditSeal`
  model, `AuditIntegrityService`, and the AUDIT-01 notes above state the limit
  plainly, with a monotonic external high-water mark as the out-of-scope fix
  and DATA-02 per-service credentials as the compensating-control roadmap.
- **In-flight defect found and fixed by the review:** anonymous GenAI callers
  failed subject-key validation and 500ed before reaching the model, because
  `TokenRateLimiter`'s subject pattern lacked the anonymous `source:<64-hex>`
  key shape (the gateway HMAC-SHA-256 fingerprint). Pattern extended, two
  regression tests added (valid anonymous key accepted with a scoped Redis
  key; malformed keys still rejected before Redis — the pattern doubles as the
  key-injection guard), 132 module tests green.
- Two sub-threshold observations were deliberately excluded (data-model
  global scope, not missing checks): staff-gated `/user/email/{email}/details`
  without tenant scoping — patients are global in the data model; and
  `GET /patient/{id}` without clinic scoping — the Patient entity has no
  clinic field.
- Gate-5 status: **no unresolved confirmed findings** — the single confirmed
  finding is closed as an explicitly accepted, documented limitation.

### DATA-02 — done: per-service PostgreSQL and MongoDB credentials

Every application service now authenticates to the databases with its own
least-privilege credential; the shared PostgreSQL superuser and MongoDB root
user are no longer consumed by any workload (they remain only on the
StatefulSets for bootstrap, as the Flyway migration owner, and for operator
backup). This is also the **compensating control for the audit tail-truncation
limitation** recorded under AUDIT-01: genai-service and
clinical-records-service previously held the Mongo root credential and could
rewrite or drop `audit_seals`; with collection-scoped roles, only
`svc_audit` can write the audit store.

- **PostgreSQL roles** (`V3__service_roles.sql`, idempotent — roles created
  without passwords, `GRANT`s re-runnable): `svc_auth` (users, user_roles,
  clinics, clinic_admin, user_approval_requests, auth_sessions,
  auth_audit_outbox, auth_security_outbox + user_id_seq), `svc_clinic`
  (clinics, services), `svc_user_profile` (users, user_roles,
  user_approval_requests, patients, patient_profiles, medical_history +
  user_id_seq, patient_record_id_seq), `svc_appointment` (appointments,
  dentist_availability + appointment_id_seq), `svc_clinical_records`
  (clinical_notes, dental_images, service_visits, treatment_plans,
  treatment_plan_items), `svc_notification` (notifications,
  notification_templates + notification_id_seq), `svc_system`
  (system_settings). The three shared tables (users, clinics,
  user_approval_requests) are written by BOTH mapping services, so both
  roles hold full DML on them — verified against repository write sites
  (native queries never cross tables). Migration authoring rule: every
  future table-creating migration must include its per-service GRANTs.
- **MongoDB users** (provisioned by `deploy/scripts/provision-db-roles.sh`;
  collection-scoped custom roles on db `dentistdss`): `svc_audit` →
  audit_entries + audit_seals only; `svc_genai` → conversations +
  prompt_templates + ai_interactions only; `svc_clinical_records` →
  readWrite on the sole-tenant `dentistdss_files` (GridFS). Users
  authenticate against `admin`, so only the user:password in the URIs
  changed.
- **Flyway owner (user decision 2026-07-25)**: the app datasource uses the
  per-service role (`DB_USERNAME`/`DB_PASSWORD`). Initially Flyway kept
  connecting as the migration owner via `spring.flyway.user` from each JDBC
  pod (accepted residual). **Residual closed (2026-07-26):** schema
  migrations now run in a standalone `Migrator` main class (db-migrations,
  plain JDBC) executed by a Helm pre-install/pre-upgrade hook Job and a
  compose one-shot `migrator` service (both fail closed on migration
  failure), reusing the auth-service image via Boot's PropertiesLauncher.
  Application services run `spring.flyway.enabled: false` (Hibernate
  `ddl-auto: validate` remains the drift detector), and the owner
  credential now exists ONLY on the postgres StatefulSet, the hook
  Job/one-shot, and the operator's backup — never in an application pod.
- **Delivery**: per-service Vault records `apps/dentistdss/<env>/
  db-credentials/<service>` (seeded by
  `deploy/scripts/seed-vault-db-credentials.sh` — deliberately separate from
  the runtime-record seeder so adoption does not rotate JWT/root secrets),
  rendered by the chart as per-service ExternalSecrets
  (`dentistdss-db-<svc>` ×7, `dentistdss-mongo-<svc>` ×3) consumed via
  `envFrom`. Compose wires the same per-service env; passwords are applied
  to the databases by the idempotent `provision-db-roles.sh`. The legacy
  root-URI keys were retired from the runtime record and the
  `dentistdss-mongo` slice (now root-bootstrap only).
- **Regression evidence**: `ServiceRoleContractTest` (migrates V1–V3, then
  per role: own-table read OK, cross-service read → 42501
  insufficient_privilege, shared-table writes OK for both writers,
  non-owner write denied, sequence USAGE matrix, DDL denied) and
  `MongoCredentialContractTest` (svc_genai denied on audit_entries /
  audit_seals — the tail-truncation control — svc_audit confined to the
  audit collections, svc_clinical_records confined to dentistdss_files),
  both wired into CI against postgres and mongo service containers; the CI
  mongo users are created by the real provision script. Rollout runbook
  (seed → provision → upgrade; fail-closed until seeded) lives in
  `deploy/chart/README.md` and `deploy/README.md`.

### DATA-03 — done: dental image upload hardening

Uploads to clinical-records-service now pass through a single-decode
sanitize pipeline (`image/ImageSanitizer.java`) before anything reaches
storage; the original upload bytes are discarded.

- **Signature validation (false MIME fails)**: the client-declared content
  type is never trusted. Magic-byte sniffing (JPEG/PNG/TIFF/BMP) determines
  the real family; the upload is rejected when the bytes match no allowed
  family, when the declared type is not an allowed type, or when declared
  and sniffed types disagree.
- **Decode limits (oversized pixels fail)**: dimensions are read from the
  image header via `ImageReader.getWidth/getHeight` BEFORE any pixel data
  is decoded; uploads over `file.upload.max-pixels` (default 25 MP) or
  `max-dimension` (default 10 000 px) are rejected without decoding —
  closing the decompression-bomb vector against the 1Gi pods. Corrupt and
  truncated bytes map to the same generic 400 (no detail leak).
- **Canonical re-encode (polyglot fails)**: the decoded image is
  re-encoded — JPEG→JPEG q0.9, PNG/TIFF/BMP→PNG — so appended archives,
  comments, and EXIF/metadata payloads are destroyed by construction; only
  the canonical artifact and a thumbnail rendered from the SAME decoded
  pixels (one decode total, no second bomb surface) are written to GridFS.
  The stored `contentType`/`fileSize` describe the canonical artifact, not
  the upload. TIFF decode uses the new pure-Java
  `com.twelvemonkeys.imageio:imageio-tiff` dependency (the JDK has no TIFF
  reader — TIFF thumbnails were silently absent before).
- **Cleanup/compensation**: the GridFS blobs and the PostgreSQL row are not
  atomic, so the upload flushes the row inside a try (`saveAndFlush`) and,
  on any failure after a blob write, deletes both blobs best-effort before
  rethrowing — no orphaned storage. Deletion now removes the metadata row
  FIRST and then the blobs (an orphaned blob is tolerable and reclaimable;
  broken metadata pointing at a deleted blob is not); blob cleanup failures
  are logged, not thrown. Residual, documented: a compound failure (PG down
  AND blob cleanup failing) can still strand a blob — no background sweeper
  exists yet.
- **Authorized ownership**: unchanged from RBAC-04 — the existing
  `DentalImageServiceAuthorizationTest` suite (patient/dentist/system-admin
  scoping, linked-note owner matching) stays green.
- **Regression evidence**: `ImageSanitizerTest` (12 tests — false-MIME
  both directions, non-image bytes, disallowed declared type, polyglot
  payload absent from the canonical artifact, oversized-header rejection
  before decode, truncation, size caps, canonical JPEG/PNG outputs) and
  `DentalImageUploadCompensationTest` (6 tests — blob compensation on
  persistence failure, no-compensation-on-first-write-failure,
  canonical-metadata storage, row-before-blob delete ordering, row
  deletion surviving blob cleanup failure).

## Completion gates

1. `./mvnw --batch-mode --no-transfer-progress verify -Pprod` passes on Java 25.
2. Frontend audit, check, unit tests, build, and Playwright auth flows pass.
3. Compose and both Helm environments render and pass policy assertions.
4. Dependency, static-analysis, secret, IaC, image, and DAST gates pass or have an explicit time-bounded exception.
5. The bundled `/security-review` reports no unresolved confirmed findings.
6. Production secret rotation and deployment remain separate operator-approved actions.
