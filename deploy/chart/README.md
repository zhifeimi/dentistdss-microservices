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

The helper generates independent database, Config Server, and 3072-bit RSA JWT
values. It prompts locally for the Vault token, Google OAuth, and SMTP values.

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
