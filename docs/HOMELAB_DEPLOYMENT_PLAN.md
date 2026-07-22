# DentistDSS homelab deployment plan

## Repository assessment

DentistDSS is split across three Git repositories:

1. `dentistdss-microservices`: Maven reactor with 13 Spring Boot applications,
   PostgreSQL, MongoDB, Eureka, Config Server, and the API Gateway.
2. `dentistdss-pwa`: React 19 and Vite PWA.
3. `dentistdss-microservices-config`: currently an empty shared
   `application.yml`.

The old production flow builds JARs on an operator machine, pushes mutable
Docker Hub tags, retags them locally, and publishes every service and database
port. It has no CI workflow, no reproducible release manifest, no safe
rollback, and no automated health-gated deployment.

## Target architecture

```text
Browser -> Vercel CDN -> DentistDSS PWA
   |
   +-> api.mizhifei.press
           |
       Cloudflare edge/WAF
           |
       Cloudflare Tunnel
           |
       10.80.20.204:8080 (MetalLB)
           |
       RKE2 production cluster
            |
       API Gateway -> Eureka -> business services
                        |          |
                        |          +-> PostgreSQL PVC
                        +------------> MongoDB PVC
```

Vercel owns static frontend delivery, preview deployments, and the production
frontend domain. The Java gateway no longer terminates public TLS. Cloudflare
owns API TLS and ingress. No database, registry, admin, config, or business
service port is bound to the host.

Argo CD on `pve-mgmt` reconciles the Helm chart into the separate `pve-dev` and
`pve-prod` RKE2 clusters. Vault and External Secrets provide runtime and GHCR
pull credentials. Docker Compose is retained only as a single-host fallback.

## Delivery pipeline

### Pull requests

- Backend: compile and test the full Maven reactor on Java 25.
- Frontend: deterministic `npm ci`, unit/component tests, TypeScript build.
- Vercel's Git integration creates preview deployments without duplicating the
  deployment in GitHub Actions.
- Validate Dockerfiles and the resolved Compose model.
- Run dependency and secret scanning.
- Never schedule pull-request code on the homelab runner.

### Main branch

- Repeat CI.
- Build one image per Java service.
- Publish multi-architecture `linux/amd64` and `linux/arm64` images to GHCR.
- Tag each image with the immutable Git commit SHA; `latest` is only a
  convenience pointer.
- Update the machine-owned `gitops-dev` branch after every successful image
  publication; Argo CD then reconciles the dev cluster.
- Promote a verified SHA tag to `gitops-prod` only through a manual workflow on
  protected `main` and the `homelab-production` environment.
- Let Vercel deploy the PWA from the same protected `main` branch.
- Let Argo CD reconcile the chart and Kubernetes health probes.

### Rollback

Run the production promotion workflow with the previous known-good backend SHA
tag. Argo CD reconciles that GitOps revision. Use Vercel's instant rollback for
the PWA. Database rollback is separate and must use a tested backup;
application rollback must never silently reverse a schema migration.

## Rollout phases

1. **Baseline and hardening**: make builds reproducible, remove runtime
   dependence on the source keystore, stop exposing internal ports, and fail
   production startup when JWT signing keys are absent.
2. **Container release**: publish non-root, multi-architecture GHCR images and
   record image digests.
3. **GitOps integration**: register the DentistDSS dev/prod Argo CD
   applications, provision an environment-scoped Vault policy and token Secret
   for each namespace, let the chart install its namespace SecretStore, and
   create the machine-owned promotion branches.
4. **Staging smoke test**: deploy to `pve-dev` with throwaway data, verify OAuth callbacks,
   email, PWA routing, every health endpoint, and restart persistence.
5. **Backups**: schedule daily PostgreSQL and MongoDB backups, encrypt and copy
   them off-host, then perform a restore drill.
6. **Ingress**: connect Cloudflare Tunnel to the production gateway MetalLB
   address and enable access logs, rate limits, and monitoring.
7. **Vercel**: retain the native Git integration, configure production-only
   `VITE_API_HOST`, and verify SPA routing and security headers.
8. **Production cutover**: update API DNS, run smoke tests, monitor error
   rates, and retain the previous SHA tags for immediate rollback.

## Required manual inputs

- Vault access for the dev/prod runtime and GHCR pull records
- Argo CD root repository synchronization from GitHub to the internal GitLab
  source, or an equivalent direct GitHub source update
- Google OAuth client ID/secret and authorized origins/redirects
- SMTP credentials
- Production JWT key pair
- Optional Vertex AI project, service account, region, and supported model

## Deferred engineering work

- Replace Hibernate schema mutation with reviewed Flyway migrations.
- Split databases or schemas per service instead of sharing one PostgreSQL
  database and one MongoDB root credential.
- Add resource limits after measuring the final host.
- Add centralized logs, metrics, alerts, and external uptime checks.
- Add an encrypted, automated off-host backup target and restore CI.
- Replace local-path database volumes with replicated storage before requiring
  node-failure tolerance.
