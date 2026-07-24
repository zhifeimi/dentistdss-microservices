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
| DATA-02 | Applications use shared/root database credentials | Per-service PostgreSQL and MongoDB users/grants | Unauthorized cross-database/table access fails | In progress |
| DATA-03 | Dental image writes and validation are unsafe | Signature/decode limits, canonical re-encode, authorized ownership, cleanup/compensation | False MIME, polyglot, oversized pixels, and unauthorized access fail | In progress |
| AUDIT-01 | Audit actor is caller-controlled and critical actions are not integrated | Server-attributed internal ingest, transactional outbox, append-only/tamper-evident store | Caller cannot forge actor; critical mutations emit immutable events | Done (see notes) |
| RATE-01 | Session and GenAI limiter maps are bypassable/unbounded | Redis-backed signed sessions, quotas, TTLs, and cardinality limits | Rotation, restart, and multi-replica tests pass without key leaks | In progress |
| ERROR-01 | Exception details leak to clients/logs | Shared safe error envelope and redaction | Responses contain no stack/SQL/SMTP/provider/token/PHI detail | In progress |
| CORS-01 | CORS and cookie behavior are broader than required | Exact origins/methods/headers, Origin validation, anti-CSRF for cookie endpoints | Hostile Origin and missing/invalid CSRF requests fail | In progress |
| SUPPLY-01 | Build inputs and vulnerabilities are not fully gated | Java 25, Enforcer, FindSecBugs, Dependency-Check, SBOM, Trivy, pinned actions/images | CI blocks policy violations and high/critical findings | In progress |

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
  sealed history would require controlling the sealer. Documents written
  before this feature carry no content hash and are never sealed; they
  surface as the report's `unsealedDocuments` backlog count.

### DATA-01 — partial: Flyway baseline and validate shipped

The `db-migrations` module with the frozen `V1__baseline.sql` and
`ddl-auto: validate` across all seven JDBC services landed as `c150186`
(PR #22); deployment `ddl-auto=update` overrides were removed, and the
baseline-on-migrate adoption path was live-verified against PostgreSQL
(both fresh-database apply and existing-database baseline). Completion
awaits PR #22 CI confirmation that all seven `*SchemaContractTest` suites
run green.

## Completion gates

1. `./mvnw --batch-mode --no-transfer-progress verify -Pprod` passes on Java 25.
2. Frontend audit, check, unit tests, build, and Playwright auth flows pass.
3. Compose and both Helm environments render and pass policy assertions.
4. Dependency, static-analysis, secret, IaC, image, and DAST gates pass or have an explicit time-bounded exception.
5. The bundled `/security-review` reports no unresolved confirmed findings.
6. Production secret rotation and deployment remain separate operator-approved actions.
