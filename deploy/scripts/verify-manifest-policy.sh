#!/usr/bin/env bash
#
# verify-manifest-policy.sh — deployment manifest security-policy gate.
#
# Wired into the CI `deployment-contract` job after the helm-template steps:
#
#   bash deploy/scripts/verify-manifest-policy.sh /tmp/dentistdss-dev.yaml /tmp/dentistdss-prod.yaml
#
# Asserts, against every rendered manifest file passed as an argument, the
# hardening contract that deploy/chart must keep:
#
#   1. Exactly 11 NetworkPolicy documents (the chart renders them
#      unconditionally; a change must be deliberate and reviewed here).
#   2. Every Deployment, StatefulSet, and Job pod:
#        - automountServiceAccountToken == false
#        - pod securityContext.runAsNonRoot == true
#      and every container / initContainer:
#        - securityContext.allowPrivilegeEscalation == false
#        - securityContext.capabilities.drop includes ALL
#        - resources.limits.cpu and resources.limits.memory are set
#   3. Deployment containers additionally:
#        - securityContext.readOnlyRootFilesystem == true
#        - livenessProbe and readinessProbe are present
#      StatefulSets are exempt from readOnlyRootFilesystem and probe
#      requirements: the PostgreSQL / Redis / MongoDB workloads manage their
#      own volume mounts and writable runtime paths; making their root
#      filesystems read-only requires emptyDir scratch mounts validated on a
#      live cluster (tracked as a follow-up, out of scope for this gate).
#      They are still held to runAsNonRoot, no privilege escalation, drop
#      ALL, and resource limits.
#   4. Images: Deployment containers must come from ghcr.io with an
#      explicit tag other than `latest` (or an @sha256: digest); all other
#      workload containers must at least carry an explicit tag other than
#      `latest` or a digest.
#   5. Secrets: every envFrom[].secretRef.name and every non-optional
#      env[].valueFrom.secretKeyRef.name must be provided by a rendered
#      ExternalSecret of the same name. (The gated genai service-auth
#      references are optional: true and therefore excluded.)
#
# Schema validation is performed first with kubeconform (strict mode),
# skipping the ExternalSecrets Operator CRDs, which live outside the core
# Kubernetes schemas.
#
# Requirements: bash, curl, shasum, and mikefarah/go yq (brew install yq).
# kubeconform is used from PATH if present, otherwise a pinned release is
# downloaded to a temp directory and checksum-verified.
#
# yq note: each multi-document manifest is loaded once as a JSON array of
# documents (`yq eval-all -o=json '[.]'`). All assertions then run with plain
# single-input yq semantics. Two empirical pitfalls shape every expression
# below (verified against mikefarah yq v4.53.3, and mutation-tested: each
# assertion family must flag a deliberately broken manifest):
#   1. Never assert under eval-all directly: after `EXPR as $x |`, a bare `.`
#      still refers to the whole document collection, which cross-multiplies
#      results across documents.
#   2. Even on plain single input, a `select(COND)` placed AFTER an `as`
#      binding mis-evaluates comparisons against the bound document (observed:
#      the select passes documents whose condition is false, while the
#      identical comparison outside select evaluates correctly). Assertions
#      therefore apply every document-level select() BEFORE any `as` binding;
#      container-level selects run only after a `[]` array explosion (which
#      behaves correctly), with workload identity carried via `. as $doc`
#      identity bindings.

set -Eeuo pipefail

readonly EXPECTED_NETWORK_POLICIES=11
readonly KUBECONFORM_VERSION="v0.8.0"
# Pinned release checksums (kubeconform CHECKSUMS asset, ${KUBECONFORM_VERSION}).
readonly KUBECONFORM_SHA_LINUX_AMD64="9bc2bffbf71f261128533edaf912153948b7ff238f9a531ae6d34466ec287883"
readonly KUBECONFORM_SHA_DARWIN_ARM64="f84f4dfbebf4a6b0b230385fa065a39ea35e02608c2b50d025dcf64775a69d67"

KUBECONFORM_BIN="kubeconform"
KUBECONFORM_TMP=""
DOCS_TMP=""
failures=0

trap 'echo "[manifest-policy] ERROR: unexpected failure at line ${LINENO}" >&2' ERR
trap '[ -n "${KUBECONFORM_TMP}" ] && rm -rf "${KUBECONFORM_TMP}";
      [ -n "${DOCS_TMP}" ] && rm -f "${DOCS_TMP}"' EXIT

log() { printf '[manifest-policy] %s\n' "$*"; }
fail() { printf '[manifest-policy] FAIL: %s\n' "$*" >&2; failures=$((failures + 1)); }

ensure_tools() {
    if ! command -v yq >/dev/null 2>&1; then
        echo "[manifest-policy] yq (mikefarah/go) is required but not installed." >&2
        echo "[manifest-policy]   macOS: brew install yq   |   linux: see https://github.com/mikefarah/yq/#install" >&2
        exit 2
    fi
    if ! yq --version 2>/dev/null | grep -qi mikefarah; then
        echo "[manifest-policy] found a yq binary, but it is not the mikefarah/go implementation." >&2
        echo "[manifest-policy] Uninstall the python wrapper and install https://github.com/mikefarah/yq" >&2
        exit 2
    fi

    if command -v kubeconform >/dev/null 2>&1; then
        return 0
    fi
    if ! command -v curl >/dev/null 2>&1; then
        echo "[manifest-policy] kubeconform ${KUBECONFORM_VERSION} is required on PATH (or install curl to auto-fetch it)." >&2
        exit 2
    fi

    local os uname_arch asset_arch sha
    os="$(uname -s | tr '[:upper:]' '[:lower:]')"
    uname_arch="$(uname -m)"
    # Release assets use Go-style arch names (amd64), not uname's (x86_64).
    case "${os}/${uname_arch}" in
        linux/x86_64) asset_arch="amd64"; sha="${KUBECONFORM_SHA_LINUX_AMD64}" ;;
        darwin/arm64) asset_arch="arm64"; sha="${KUBECONFORM_SHA_DARWIN_ARM64}" ;;
        *)
            echo "[manifest-policy] unsupported platform ${os}/${uname_arch} for auto-download;" >&2
            echo "[manifest-policy] install kubeconform ${KUBECONFORM_VERSION} manually and re-run." >&2
            exit 2
            ;;
    esac

    KUBECONFORM_TMP="$(mktemp -d)"
    local archive="kubeconform-${os}-${asset_arch}.tar.gz"
    local url="https://github.com/yannh/kubeconform/releases/download/${KUBECONFORM_VERSION}/${archive}"
    log "downloading kubeconform ${KUBECONFORM_VERSION} for ${os}/${uname_arch}"
    curl -fsSL "${url}" -o "${KUBECONFORM_TMP}/${archive}"
    printf '%s  %s\n' "${sha}" "${KUBECONFORM_TMP}/${archive}" | shasum -a 256 -c - >/dev/null
    tar -xzf "${KUBECONFORM_TMP}/${archive}" -C "${KUBECONFORM_TMP}" kubeconform
    chmod +x "${KUBECONFORM_TMP}/kubeconform"
    KUBECONFORM_BIN="${KUBECONFORM_TMP}/kubeconform"
}

# load_docs <manifest-file> <docs-json-file>
# Collapses a multi-document YAML manifest into a single JSON array of
# documents so every assertion can use plain single-input yq semantics.
load_docs() {
    yq eval-all -o=json '[.]' "$1" > "$2"
}

# report_violations <manifest-file> <docs-json-file> <yq-expression>
# The expression runs against the JSON document array and must emit one
# human-readable violation line per failing resource; no output means the
# assertion passed.
report_violations() {
    local file="$1" docs="$2" expr="$3" out line
    if ! out="$(yq "${expr}" "${docs}")"; then
        fail "${file}: policy query failed to evaluate"
        return
    fi
    while IFS= read -r line; do
        [ -n "${line}" ] || continue
        [ "${line}" = "null" ] && continue
        [ "${line}" = "---" ] && continue
        fail "${file}: ${line}"
    done <<<"${out}"
}

kubeconform_check() {
    local file="$1"
    log "kubeconform -strict schema validation: ${file}"
    if ! "${KUBECONFORM_BIN}" -strict -summary \
        -skip ExternalSecret,SecretStore,ClusterSecretStore "${file}"; then
        fail "${file}: kubeconform schema validation failed"
    fi
}

assert_network_policies() {
    local file="$1" docs="$2" count
    count="$(yq '[.[] | select(.kind == "NetworkPolicy")] | length' "${docs}")" || count=0
    if [ "${count}" -ne "${EXPECTED_NETWORK_POLICIES}" ]; then
        fail "${file}: expected ${EXPECTED_NETWORK_POLICIES} NetworkPolicy documents, found ${count}"
    fi
}

assert_pod_security() {
    local file="$1" docs="$2"
    # Pod-level checks: all selects run before any `as` binding (see yq note
    # 2); the workload name is computed in the final pipeline stage.
    report_violations "${file}" "${docs}" '
        .[] | select(.kind == "Deployment" or .kind == "StatefulSet" or .kind == "Job")
        | select(.spec.template.spec.automountServiceAccountToken != false)
        | (.kind + "/" + .metadata.name)
            + ": automountServiceAccountToken must be false"
    '
    report_violations "${file}" "${docs}" '
        .[] | select(.kind == "Deployment" or .kind == "StatefulSet" or .kind == "Job")
        | select(.spec.template.spec.securityContext.runAsNonRoot != true)
        | (.kind + "/" + .metadata.name)
            + ": pod securityContext.runAsNonRoot must be true"
    '
    # Container-level checks: identity-bind the document, explode containers,
    # then select on the expanded container context (selects after `[]` are
    # well-behaved; see yq note 2).
    report_violations "${file}" "${docs}" '
        .[] | select(.kind == "Deployment" or .kind == "StatefulSet" or .kind == "Job")
        | . as $doc
        | ((.spec.template.spec.containers // [])
            + (.spec.template.spec.initContainers // []))[]
        | select(.securityContext.allowPrivilegeEscalation != false)
        | ($doc.kind + "/" + $doc.metadata.name) + " container " + .name
            + ": allowPrivilegeEscalation must be false"
    '
    report_violations "${file}" "${docs}" '
        .[] | select(.kind == "Deployment" or .kind == "StatefulSet" or .kind == "Job")
        | . as $doc
        | ((.spec.template.spec.containers // [])
            + (.spec.template.spec.initContainers // []))[]
        | select(((.securityContext.capabilities.drop // []) | contains(["ALL"])) | not)
        | ($doc.kind + "/" + $doc.metadata.name) + " container " + .name
            + ": capabilities.drop must include ALL"
    '
    report_violations "${file}" "${docs}" '
        .[] | select(.kind == "Deployment" or .kind == "StatefulSet" or .kind == "Job")
        | . as $doc
        | ((.spec.template.spec.containers // [])
            + (.spec.template.spec.initContainers // []))[]
        | select(.resources.limits.cpu == null or .resources.limits.memory == null)
        | ($doc.kind + "/" + $doc.metadata.name) + " container " + .name
            + ": resources.limits.cpu and resources.limits.memory must be set"
    '
}

assert_deployment_containers() {
    local file="$1" docs="$2"
    # StatefulSets are intentionally exempt — see the header comment.
    report_violations "${file}" "${docs}" '
        .[] | select(.kind == "Deployment")
        | . as $doc
        | (.spec.template.spec.containers // [])[]
        | select(.securityContext.readOnlyRootFilesystem != true)
        | ("Deployment/" + $doc.metadata.name) + " container " + .name
            + ": readOnlyRootFilesystem must be true"
    '
    report_violations "${file}" "${docs}" '
        .[] | select(.kind == "Deployment")
        | . as $doc
        | (.spec.template.spec.containers // [])[]
        | select(.livenessProbe == null)
        | ("Deployment/" + $doc.metadata.name) + " container " + .name
            + ": livenessProbe is required"
    '
    report_violations "${file}" "${docs}" '
        .[] | select(.kind == "Deployment")
        | . as $doc
        | (.spec.template.spec.containers // [])[]
        | select(.readinessProbe == null)
        | ("Deployment/" + $doc.metadata.name) + " container " + .name
            + ": readinessProbe is required"
    '
}

assert_images() {
    local file="$1" docs="$2"
    report_violations "${file}" "${docs}" '
        .[] | select(.kind == "Deployment")
        | . as $doc
        | ((.spec.template.spec.containers // [])
            + (.spec.template.spec.initContainers // []))[]
        | select(((.image // "") | test("^ghcr\\.io/")) | not)
        | ("Deployment/" + $doc.metadata.name) + " container " + .name
            + ": image must come from ghcr.io, got " + (.image // "")
    '
    report_violations "${file}" "${docs}" '
        .[] | select(.kind == "Deployment" or .kind == "StatefulSet" or .kind == "Job")
        | . as $doc
        | ((.spec.template.spec.containers // [])
            + (.spec.template.spec.initContainers // []))[]
        | select(
            (((.image // "") | contains("@sha256:"))
                or (((.image // "") | test(":[A-Za-z0-9][A-Za-z0-9._-]*$"))
                    and (((.image // "") | test(":latest$")) | not))) | not)
        | ($doc.kind + "/" + $doc.metadata.name) + " container " + .name
            + ": image needs an explicit non-latest tag or @sha256 digest, got "
            + (.image // "")
    '
}

assert_secrets() {
    local file="$1" docs="$2" ext_list refs entry pod_container secret
    ext_list="$(yq '.[] | select(.kind == "ExternalSecret") | .metadata.name' "${docs}" \
        | grep -v -e '^---$' -e '^$' | sort -u)" || ext_list=""
    refs="$(yq '
        .[] | select(.kind == "Deployment" or .kind == "StatefulSet" or .kind == "Job")
        | . as $doc
        | ((.spec.template.spec.containers // [])
            + (.spec.template.spec.initContainers // []))[]
        | . as $c
        | (
            (($c.envFrom // [])[] | select(.secretRef != null)
                | ($doc.kind + "/" + $doc.metadata.name) + " container "
                    + $c.name + "=" + .secretRef.name),
            (($c.env // [])[] | select(.valueFrom.secretKeyRef != null)
                | select(.valueFrom.secretKeyRef.optional != true)
                | ($doc.kind + "/" + $doc.metadata.name) + " container "
                    + $c.name + "=" + .valueFrom.secretKeyRef.name)
          )
    ' "${docs}")" || refs=""
    while IFS= read -r entry; do
        [ -n "${entry}" ] || continue
        [ "${entry}" = "null" ] && continue
        [ "${entry}" = "---" ] && continue
        pod_container="${entry%%=*}"
        secret="${entry#*=}"
        if ! printf '%s\n' "${ext_list}" | grep -qxF "${secret}"; then
            fail "${file}: ${pod_container} references secret '${secret}' not provided by any rendered ExternalSecret"
        fi
    done <<<"${refs}"
}

main() {
    if [ "$#" -lt 1 ]; then
        echo "usage: $(basename "$0") <rendered-manifest.yaml> [...]" >&2
        exit 2
    fi

    ensure_tools

    DOCS_TMP="$(mktemp)"
    local file
    for file in "$@"; do
        if [ ! -f "${file}" ]; then
            fail "manifest file not found: ${file}"
            continue
        fi
        log "checking ${file}"
        kubeconform_check "${file}"
        load_docs "${file}" "${DOCS_TMP}"
        assert_network_policies "${file}" "${DOCS_TMP}"
        assert_pod_security "${file}" "${DOCS_TMP}"
        assert_deployment_containers "${file}" "${DOCS_TMP}"
        assert_images "${file}" "${DOCS_TMP}"
        assert_secrets "${file}" "${DOCS_TMP}"
    done

    if [ "${failures}" -gt 0 ]; then
        log "FAILED: ${failures} policy violation(s)"
        exit 1
    fi
    log "all manifest policy assertions passed"
}

main "$@"
