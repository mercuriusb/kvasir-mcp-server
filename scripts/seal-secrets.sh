#!/usr/bin/env bash
#
# Produces the two SealedSecrets the deployment needs. Run it against the cluster that will
# decrypt them: kubeseal encrypts with the public key of THAT cluster's controller, and a
# SealedSecret is bound to its namespace and name - the same file cannot be moved to another
# namespace or renamed.
#
#     scripts/seal-secrets.sh <output-directory>
#
# The manifests live in the GitOps repository, so that is where the output belongs:
#
#     scripts/seal-secrets.sh ../k8s-flux/clusters/home/apps/kvasir
#
# The S3 credentials are read from .env, so they never appear on a command line, where they would
# land in the shell history and in the process list of every user on the machine. Registry
# credentials are asked for interactively, for the same reason.
#
# The output files are encrypted and belong in Git - that is the whole point of Sealed Secrets.
# Only the controller in the cluster can read them.
set -euo pipefail

NAMESPACE=kvasir

# Read out of the git remote rather than written down: the host belongs to whoever cloned this,
# and a value in the file would be both wrong for them and a needless disclosure in a public
# repository. Override with REGISTRY=... if the images live somewhere else than the source.
REGISTRY="${REGISTRY:-}"

[[ $# -eq 1 ]] || { echo "Usage: $0 <output-directory>   (in the GitOps repository)" >&2; exit 2; }

# Resolved to an absolute path BEFORE changing directory, so that a relative argument means what
# the caller meant by it. Afterwards it would silently be taken as relative to the project root.
OUT="$(cd "$1" 2>/dev/null && pwd)" \
    || { echo "No such directory: $1" >&2; exit 1; }

cd "$(dirname "$0")/.."

# Host of the origin remote: https://host/..., ssh://git@host:port/... and git@host:... all reduce
# to the same thing. Images and source live on the same forge here, which is what makes this a
# derivation rather than a guess.
if [[ -z "$REGISTRY" ]]; then
    REGISTRY=$(git remote get-url origin 2>/dev/null \
        | sed -E 's|^[a-z]+://||; s|^[^@/]*@||; s|[:/].*$||')
fi
[[ -n "$REGISTRY" ]] || { echo "Could not derive the registry from the origin remote. Set REGISTRY=..." >&2; exit 1; }

for tool in kubectl kubeseal; do
    command -v "$tool" >/dev/null || { echo "$tool not found in PATH" >&2; exit 1; }
done

# kubeseal looks for a service called sealed-secrets-controller in kube-system, which is not what a
# Helm install produces - there the service is named after the release. Getting this wrong fails
# with "cannot get sealed secret service", which reads like the controller is missing rather than
# like it is called something else.
#
# So the name is looked up by label instead of assumed, and can be overridden:
#     SEALED_SECRETS_NAME=... SEALED_SECRETS_NAMESPACE=... scripts/seal-secrets.sh <dir>
CONTROLLER_NAMESPACE="${SEALED_SECRETS_NAMESPACE:-kube-system}"
CONTROLLER_NAME="${SEALED_SECRETS_NAME:-}"
if [[ -z "$CONTROLLER_NAME" ]]; then
    CONTROLLER_NAME=$(kubectl --namespace "$CONTROLLER_NAMESPACE" get svc \
        --selector app.kubernetes.io/name=sealed-secrets \
        --output jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
fi
[[ -n "$CONTROLLER_NAME" ]] || {
    echo "No sealed-secrets service found in namespace $CONTROLLER_NAMESPACE." >&2
    echo "Find it with:  kubectl get svc -A | grep -i sealed" >&2
    echo "Then set SEALED_SECRETS_NAME and, if needed, SEALED_SECRETS_NAMESPACE." >&2
    exit 1
}
echo "Sealing against controller $CONTROLLER_NAME in $CONTROLLER_NAMESPACE"

# One place for the two flags, so the two kubeseal calls below cannot drift apart.
KUBESEAL=(kubeseal --format yaml
          --controller-name "$CONTROLLER_NAME"
          --controller-namespace "$CONTROLLER_NAMESPACE")

[[ -f .env ]] || { echo "No .env - copy .env.example and fill it in." >&2; exit 1; }

# shellcheck disable=SC1091
set -a; source .env; set +a

: "${AWS_ACCESS_KEY_ID:?not set in .env}"
: "${AWS_SECRET_ACCESS_KEY:?not set in .env}"

echo "Sealing the S3 credentials for namespace $NAMESPACE ..."
# --dry-run=client keeps the plain Secret from ever reaching the cluster; only its sealed form is
# written, and only to a file.
kubectl create secret generic kvasir-s3 \
    --namespace "$NAMESPACE" \
    --from-literal=AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
    --from-literal=AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
    --dry-run=client -o yaml \
    | "${KUBESEAL[@]}" > "$OUT/sealed-secret-s3.yaml"
echo "  -> $OUT/sealed-secret-s3.yaml"

echo
echo "Registry credentials for $REGISTRY (a Forgejo token works as the password):"
read -r -p "  username: " REGISTRY_USER
read -r -s -p "  token:    " REGISTRY_TOKEN
echo

kubectl create secret docker-registry kvasir-registry \
    --namespace "$NAMESPACE" \
    --docker-server="$REGISTRY" \
    --docker-username="$REGISTRY_USER" \
    --docker-password="$REGISTRY_TOKEN" \
    --dry-run=client -o yaml \
    | "${KUBESEAL[@]}" > "$OUT/sealed-secret-registry.yaml"
echo "  -> $OUT/sealed-secret-registry.yaml"

echo
echo "Now uncomment both files in $OUT/kustomization.yaml and commit them there."
