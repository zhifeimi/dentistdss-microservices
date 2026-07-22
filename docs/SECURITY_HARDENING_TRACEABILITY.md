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
| DATA-01 | Shared DB schema mutates through Hibernate | Versioned Flyway owner and `ddl-auto: validate` everywhere | Migration succeeds against baseline; all services validate | In progress |
| DATA-02 | Applications use shared/root database credentials | Per-service PostgreSQL and MongoDB users/grants | Unauthorized cross-database/table access fails | In progress |
| DATA-03 | Dental image writes and validation are unsafe | Signature/decode limits, canonical re-encode, authorized ownership, cleanup/compensation | False MIME, polyglot, oversized pixels, and unauthorized access fail | In progress |
| AUDIT-01 | Audit actor is caller-controlled and critical actions are not integrated | Server-attributed internal ingest, transactional outbox, append-only/tamper-evident store | Caller cannot forge actor; critical mutations emit immutable events | In progress |
| RATE-01 | Session and GenAI limiter maps are bypassable/unbounded | Redis-backed signed sessions, quotas, TTLs, and cardinality limits | Rotation, restart, and multi-replica tests pass without key leaks | In progress |
| ERROR-01 | Exception details leak to clients/logs | Shared safe error envelope and redaction | Responses contain no stack/SQL/SMTP/provider/token/PHI detail | In progress |
| CORS-01 | CORS and cookie behavior are broader than required | Exact origins/methods/headers, Origin validation, anti-CSRF for cookie endpoints | Hostile Origin and missing/invalid CSRF requests fail | In progress |
| SUPPLY-01 | Build inputs and vulnerabilities are not fully gated | Java 25, Enforcer, FindSecBugs, Dependency-Check, SBOM, Trivy, pinned actions/images | CI blocks policy violations and high/critical findings | In progress |

## Completion gates

1. `./mvnw --batch-mode --no-transfer-progress verify -Pprod` passes on Java 25.
2. Frontend audit, check, unit tests, build, and Playwright auth flows pass.
3. Compose and both Helm environments render and pass policy assertions.
4. Dependency, static-analysis, secret, IaC, image, and DAST gates pass or have an explicit time-bounded exception.
5. The bundled `/security-review` reports no unresolved confirmed findings.
6. Production secret rotation and deployment remain separate operator-approved actions.
