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

4. Authenticate Docker to GHCR if the packages are private.
5. Run `./scripts/deploy.sh`.
6. Configure Cloudflare Tunnel and test the public API URL.
7. Run `./scripts/backup.sh`, copy the backup off-host, and verify a restore
   before treating the deployment as durable.

## CI/CD contract

Compose is not run by GitHub Actions. GitHub-hosted runners publish images and
update the machine-owned `gitops-dev` or `gitops-prod` branch. Argo CD deploys
the Helm chart to the corresponding RKE2 cluster. The protected
`homelab-production` environment gates production promotion.

## Known database migration debt

The legacy `docker-entrypoint-initdb.d/01-init.sql` file is DBML, not executable
PostgreSQL. It is deliberately not mounted here. Two services previously used
Hibernate `validate` without a migration that created their tables, so this
compose file temporarily overrides them to `update` for bootstrapping.

Replace this with versioned Flyway migrations, change every production service
to `ddl-auto: validate`, and test restore plus forward migration before storing
real patient data.
