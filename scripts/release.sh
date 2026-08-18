#!/usr/bin/env bash
#
# Cuts a release: tags the current commit, which is what makes the workflow build an image named
# after that version.
#
#     scripts/release.sh 1.2.3
#
# This does NOT roll anything out. Building and deploying are separate on purpose:
#
#     git tag v1.2.3   ->  image <registry>/<owner>/kvasir-mcp-server:1.2.3 exists
#     k8s-flux         ->  newTag: "1.2.3" in clusters/home/apps/kvasir/kustomization.yaml
#
# The second step is a commit in the GitOps repository, and it is the one the cluster follows.
# Keeping it separate is what lets configuration be changed there without a new application
# release, and an application be released without touching the cluster.
set -euo pipefail

cd "$(dirname "$0")/.."

[[ $# -eq 1 ]] || { echo "Usage: $0 <version>   e.g. $0 1.2.3" >&2; exit 2; }

# Accept 1.2.3 and v1.2.3 alike; the tag carries the v, the image tag does not.
VERSION="${1#v}"
TAG="v$VERSION"

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] \
    || { echo "Not a semantic version: $VERSION" >&2; exit 1; }

BRANCH=$(git rev-parse --abbrev-ref HEAD)
[[ "$BRANCH" == "main" ]] || { echo "On branch $BRANCH - release from main." >&2; exit 1; }

[[ -z "$(git status --porcelain)" ]] || { echo "Working tree is not clean." >&2; exit 1; }

git rev-parse -q --verify "refs/tags/$TAG" >/dev/null \
    && { echo "Tag $TAG already exists. An image tag must never be rebuilt with different content." >&2; exit 1; }

git tag -a "$TAG" -m "Release $TAG"
git push origin "$BRANCH"
git push origin "$TAG"

cat <<EOF

Tagged $TAG and pushed. The workflow is now building:

    <registry>/<owner>/kvasir-mcp-server:$VERSION

Nothing in the cluster has changed yet. To roll it out, set the tag in the GitOps repository and
commit - wait for the build to finish first, or the pod will sit in ImagePullBackOff until it does:

    clusters/home/apps/kvasir/kustomization.yaml
        newTag: "$VERSION"
EOF
