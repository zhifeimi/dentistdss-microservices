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
Internet or LAN
      |
Cloudflare Tunnel or existing TLS reverse proxy
      |
127.0.0.1:8080
      |
PWA/Nginx container
      |
private Docker network
      |
API Gateway (HTTP 8080) -> Eureka -> business services
                           |          |
                           |          +-> PostgreSQL
                           +------------> MongoDB
```

The Java gateway no longer terminates public TLS. This removes the committed
keystore from the release path and gives certificates one owner at the edge.
No database, registry, admin, config, or application service port is bound to
the host.

## Delivery pipeline

### Pull requests

- Backend: compile and test the full Maven reactor on Java 21.
- Frontend: deterministic `npm ci`, unit/component tests, TypeScript build.
- Validate Dockerfiles and the resolved Compose model.
- Run dependency and secret scanning.
- Never schedule pull-request code on the homelab runner.

### Main branch

- Repeat CI.
- Build one image per Java service and one PWA image.
- Publish multi-architecture `linux/amd64` and `linux/arm64` images to GHCR.
- Tag each image with the immutable Git commit SHA; `latest` is only a
  convenience pointer.
- Deploy the SHA tag through the protected `homelab-production` environment.
- Wait for Compose health checks and run HTTP smoke tests.

### Rollback

Run the deployment workflow manually with the previous known-good backend and
frontend SHA tags. Compose pulls those immutable tags and recreates only
changed containers. Database rollback is separate and must use a tested backup;
application rollback must never silently reverse a schema migration.

## Rollout phases

1. **Baseline and hardening**: make builds reproducible, remove runtime
   dependence on the source keystore, stop exposing internal ports, and fail
   production startup when JWT signing keys are absent.
2. **Container release**: publish non-root, multi-architecture GHCR images and
   record image digests.
3. **Homelab runner**: install a dedicated self-hosted Actions runner on the
   final Linux node, label it, restrict the repository and environment, and
   confirm Docker access.
4. **Staging smoke test**: deploy with throwaway data, verify OAuth callbacks,
   email, PWA routing, every health endpoint, and restart persistence.
5. **Backups**: schedule daily PostgreSQL and MongoDB backups, encrypt and copy
   them off-host, then perform a restore drill.
6. **Ingress**: connect the existing reverse proxy or Cloudflare Tunnel to the
   loopback PWA port and enable access logs, rate limits, and monitoring.
7. **Production cutover**: update DNS, run smoke tests, monitor error rates,
   and retain the previous SHA tags for immediate rollback.

## Required manual inputs

- Final application host and its CPU, RAM, disk, OS, and architecture
- Public hostname and choice of existing reverse proxy versus Cloudflare Tunnel
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
