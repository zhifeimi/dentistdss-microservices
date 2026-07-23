# DentistDSS Helm chart

This is the primary deployment contract for the existing RKE2/Argo CD homelab
platform. GitHub Actions builds images; Argo CD renders this chart from the
`gitops-dev` or `gitops-prod` branch. Docker Compose remains the recovery and
single-host fallback.

## Platform contract

- dev namespace: `dev-dentistdss`, gateway address `10.80.30.204`
- prod namespace: `prod-dentistdss`, gateway address `10.80.20.204`
- storage class: `local-path`
- secret store: namespace `SecretStore/homelab-vault-dentistdss`
- registry: `ghcr.io/zhifeimi`
- production ingress: Cloudflare Tunnel to `http://10.80.20.204:8080`
- backend pods are spread across RKE2 nodes by hostname
- dev uses non-surging rollouts, a 768 MiB Java container limit, and a 50% heap cap
- application logs go to stdout; `/tmp` remains RAM-backed for temporary files

The `bootstrap` image tag is intentionally non-runnable. The release workflow
creates the GitOps branches only after all commit-SHA images exist and replaces
that tag before Argo CD can reconcile the application.

## Vault records

Create these KV v2 records:

- `kv/apps/dentistdss/dev/runtime`
- `kv/apps/dentistdss/dev/genai-service-auth`
- `kv/apps/dentistdss/prod/runtime`
- `kv/apps/dentistdss/prod/genai-service-auth`

Each environment requires a `vault-dentistdss-token` Secret in its application
namespace. The token must have read access only to that environment's runtime
record. The chart creates the namespace-scoped SecretStore that consumes the
token; never restore the former cluster-wide Vault store.

Runtime records require:

```text
POSTGRES_PASSWORD
MONGO_INITDB_ROOT_PASSWORD
SPRING_CONFIG_USER
SPRING_CONFIG_PASS
REDIS_PASSWORD
ANONYMOUS_SESSION_FINGERPRINT_KEY
EMAIL_VERIFICATION_CODE_PEPPER
JWT_RSA_PRIVATE_KEY
JWT_RSA_PUBLIC_KEY
JWT_RSA_KEY_ID
GOOGLE_CLIENT_ID
GOOGLE_CLIENT_SECRET
MAIL_HOST
MAIL_PORT
MAIL_USERNAME
MAIL_PASSWORD
SPRING_DATA_MONGODB_URI
MONGODB_URI
```

The chart never mounts the runtime record wholesale. `externalSecrets.records`
in `values.yaml` maps each Kubernetes Secret name to the record properties it
may contain, and one ExternalSecret is rendered per entry. Each service then
lists exactly the Secrets it needs under `services.<name>.secrets`, so a
compromised pod only reads the credentials for its own databases and keys.
Keep the record schema above and the `records` map in sync when adding keys.

Each `genai-service-auth` record requires a dedicated gateway-to-GenAI key pair:

```text
GENAI_SERVICE_AUTH_PRIVATE_KEY
GENAI_SERVICE_AUTH_PUBLIC_KEY
GENAI_SERVICE_AUTH_KEY_ID
```

Use a single-line PKCS#8 private key and X.509 public key. The chart references
all three values only from `api-gateway`; `genai-service` receives only the
public key and key ID. Do not add these values to the broad runtime record.

Seed each runtime record interactively without printing credentials:

```bash
./deploy/scripts/seed-vault-runtime.sh dev
./deploy/scripts/seed-vault-runtime.sh prod
```

The helper generates independent database, Config Server, Redis, fingerprint,
verification-code pepper, and 3072-bit RSA JWT values. It prompts locally for
the Vault token, Google OAuth, and SMTP values.

## Workloads and network policy

The chart renders `postgres`, `mongo`, and `redis` StatefulSets alongside the
service Deployments. Redis is authenticated: it starts with
`--requirepass "$REDIS_PASSWORD"` from the `dentistdss-redis` Secret, and every
consumer in `values.yaml` receives `REDIS_HOST`/`REDIS_PORT` plus the password.
Production service configuration requires the Redis password with no default,
so a missing Secret fails startup instead of silently opening an
unauthenticated connection.

NetworkPolicies default-deny the namespace and then allow only:

- intra-namespace traffic to application pods (databases excluded);
- PostgreSQL ingress from the seven JDBC services on 5432;
- MongoDB ingress from `clinical-records-service`, `audit-service`, and
  `genai-service` on 27017;
- Redis ingress from its seven consumers on 6379;
- gateway-to-GenAI and external LoadBalancer ingress to `api-gateway`;
- DNS egress to `kube-system` and selected external egress for pods labeled
  `dentistdss.io/external-egress: "true"` (CGNAT and link-local ranges stay
  blocked).

When a service gains or loses a database dependency, update both its
`secrets` list and the matching `allow-*-clients` policy.

The release workflow makes the repository-linked GHCR images public before it
updates either GitOps branch, so no long-lived registry credential is stored in
the cluster.

## Validation

```bash
helm lint deploy/chart -f deploy/chart/values-dev.yaml
helm template dentistdss deploy/chart \
  --namespace dev-dentistdss \
  -f deploy/chart/values-dev.yaml |
  kubeconform -strict -ignore-missing-schemas -summary
```

ExternalSecret resources are skipped by `kubeconform` unless their CRD schema
is supplied separately.
