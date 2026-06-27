# DentistDSS Helm chart

This is the primary deployment contract for the existing RKE2/Argo CD homelab
platform. GitHub Actions builds images; Argo CD renders this chart from the
`gitops-dev` or `gitops-prod` branch. Docker Compose remains the recovery and
single-host fallback.

## Platform contract

- dev namespace: `dev-dentistdss`, gateway address `10.80.30.204`
- prod namespace: `prod-dentistdss`, gateway address `10.80.20.204`
- storage class: `local-path`
- secret store: `ClusterSecretStore/homelab-vault`
- registry: `ghcr.io/zhifeimi`
- production ingress: Cloudflare Tunnel to `http://10.80.20.204:8080`

The `bootstrap` image tag is intentionally non-runnable. The release workflow
creates the GitOps branches only after all commit-SHA images exist and replaces
that tag before Argo CD can reconcile the application.

## Vault records

Create these KV v2 records:

- `kv/apps/dentistdss/dev/runtime`
- `kv/apps/dentistdss/dev/registry`
- `kv/apps/dentistdss/prod/runtime`
- `kv/apps/dentistdss/prod/registry`

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

The registry records require one `dockerconfigjson` property containing a
complete Docker config JSON document with read access to the DentistDSS GHCR
packages. Never commit either record.

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
