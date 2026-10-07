#!/usr/bin/env bash
# Downloads the sources JARs of a Maven project's dependencies into the kvasir data directory.
#
# The point is not to mirror a repository but to index the libraries someone actually reads while
# working on the project. Three selections are offered, from narrow to wide:
#
#   --direct   the dependencies declared in the pom (default)
#   --used     every dependency, direct or transitive, whose packages appear in an import
#              statement of this project - the honest answer to "what do I actually call?"
#   --all      the whole resolved dependency tree
#
# Layout written (one kvasir project per artifact, see docs):
#
#   <data-dir>/<artifactId>/<version>/<artifactId>-<version>-sources.jar
#
# Usage:
#   scripts/import-maven-sources.sh [options] [maven-project-dir]
#
# Options:
#   --direct | --used | --all   selection, default --direct
#   --scope <scope>             Maven scope filter, default compile (use "" for every scope)
#   --data-dir <dir>            kvasir data directory, default ./data
#   --include <g:a>             additionally take this artifact, repeatable. For the libraries an
#                               extension only wraps - a Quarkus extension's own sources are glue,
#                               the API you search for sits one level below it
#   --index-url <url>           POST <url>/admin/index afterwards, e.g. http://localhost:8080
#   --dry-run                   list what would be downloaded and stop
#
set -euo pipefail

SELECTION=direct
SCOPE=compile
DATA_DIR=""
INDEX_URL=""
DRY_RUN=false
PROJECT_DIR=""
declare -a EXTRA_INCLUDES=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --direct)    SELECTION=direct;  shift ;;
        --used)      SELECTION=used;    shift ;;
        --all)       SELECTION=all;     shift ;;
        --scope)     SCOPE="$2";        shift 2 ;;
        --data-dir)  DATA_DIR="$2";     shift 2 ;;
        --include)   EXTRA_INCLUDES+=("$2"); shift 2 ;;
        --index-url) INDEX_URL="${2%/}"; shift 2 ;;
        --dry-run)   DRY_RUN=true;      shift ;;
        -h|--help)   sed -n '2,30p' "$0"; exit 0 ;;
        -*)          echo "unknown option: $1" >&2; exit 2 ;;
        *)           PROJECT_DIR="$1";  shift ;;
    esac
done

PROJECT_DIR="$(cd "${PROJECT_DIR:-.}" && pwd)"
KVASIR_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA_DIR="${DATA_DIR:-$KVASIR_ROOT/data}"

[[ -f "$PROJECT_DIR/pom.xml" ]] || { echo "no pom.xml in $PROJECT_DIR" >&2; exit 1; }

# The project's own wrapper if it has one: a project pinned to a Maven version should be resolved
# with it, not with whatever happens to be on PATH.
MVN="mvn"
[[ -x "$PROJECT_DIR/mvnw" ]] && MVN="$PROJECT_DIR/mvnw"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

scope_args() {
    [[ -n "$SCOPE" ]] && printf '%s' "-DincludeScope=$SCOPE"
}

echo "==> Resolving dependencies of $PROJECT_DIR (selection: $SELECTION, scope: ${SCOPE:-all})"

# ---------------------------------------------------------------------------------------------
# Step 1: decide which artifacts are wanted, as groupId:artifactId:version lines.
# ---------------------------------------------------------------------------------------------
wanted="$WORK/wanted.txt"
: > "$wanted"

list_dependencies() {  # $1 = extra flags for dependency:list
    local out="$WORK/list.txt"
    # shellcheck disable=SC2046
    (cd "$PROJECT_DIR" && "$MVN" -q dependency:list \
        -DoutputFile="$out" -DoutputAbsoluteArtifactFilename=false -DincludeTypes=jar \
        $(scope_args) $1 >/dev/null)
    # "   group:artifact:jar:version:scope"  ->  "group:artifact:version"
    sed -e 's/^[[:space:]]*//' "$out" \
        | grep -E '^[^:]+:[^:]+:jar:' \
        | awk -F: '{print $1":"$2":"$4}' \
        | sort -u
}

case "$SELECTION" in
    direct) list_dependencies "-DexcludeTransitive=true" > "$wanted" ;;
    all)    list_dependencies "" > "$wanted" ;;
    used)
        # Which packages does this project import? Then keep every dependency that ships one of
        # them. The binary JARs are already in the local repository because the build resolved
        # them, so this costs no download - only the matches are fetched as sources.
        imports="$WORK/imports.txt"
        find "$PROJECT_DIR" -path '*/target' -prune -o -name '*.java' -print \
            | xargs -r grep -hE '^\s*import\s+(static\s+)?[a-zA-Z]' \
            | sed -E 's/^\s*import\s+(static\s+)?//; s/;.*$//' \
            | sed -E 's/\.[A-Z][^.]*.*$//' \
            | sort -u > "$imports"
        echo "    $(wc -l < "$imports") distinct imported packages"

        classpath="$WORK/cp.txt"
        (cd "$PROJECT_DIR" && "$MVN" -q dependency:build-classpath \
            -Dmdep.outputFile="$classpath" -Dmdep.pathSeparator=$'\n' $(scope_args) >/dev/null)

        list_dependencies "" > "$WORK/all.txt"
        while IFS= read -r jar; do
            [[ -f "$jar" ]] || continue
            # packages shipped by this jar
            if unzip -Z1 "$jar" 2>/dev/null | grep '\.class$' \
                 | sed -E 's|/[^/]+\.class$||; s|/|.|g' | sort -u \
                 | grep -qxFf "$imports"; then
                base="$(basename "$jar" .jar)"
                grep -E ":${base%-*}:${base##*-}\$" "$WORK/all.txt" >> "$wanted" || true
            fi
        done < "$classpath"
        sort -u -o "$wanted" "$wanted"
        ;;
esac

for ga in "${EXTRA_INCLUDES[@]:-}"; do
    [[ -z "$ga" ]] && continue
    if grep -qE "^${ga}:" "$WORK/all.txt" 2>/dev/null; then
        grep -E "^${ga}:" "$WORK/all.txt" >> "$wanted"
    else
        list_dependencies "" > "$WORK/all.txt"
        grep -E "^${ga}:" "$WORK/all.txt" >> "$wanted" \
            || echo "    !! --include $ga is not in the dependency tree, ignored" >&2
    fi
done
sort -u -o "$wanted" "$wanted"

count=$(wc -l < "$wanted")
echo "==> $count artifact(s) selected"
[[ "$count" -eq 0 ]] && { echo "nothing to do"; exit 0; }
sed 's/^/    /' "$wanted"

if [[ "$DRY_RUN" == true ]]; then
    echo "==> --dry-run, stopping here"
    exit 0
fi

# ---------------------------------------------------------------------------------------------
# Step 2: fetch the sources JARs. One Maven invocation for all of them; the repository layout is
# used because artifactId and version are then two directory names instead of a filename that has
# to be taken apart - artifact ids contain dashes and so do versions.
# ---------------------------------------------------------------------------------------------
staging="$WORK/repo"

# dependency:copy takes exactly one artifact, so the whole selection goes through
# copy-dependencies instead, narrowed by artifact id. One invocation, one JVM start.
include_ids="$(awk -F: '{print $2}' "$wanted" | sort -u | paste -sd, -)"

echo "==> Downloading sources JARs"
(cd "$PROJECT_DIR" && "$MVN" -q dependency:copy-dependencies \
    -Dclassifier=sources \
    -DincludeArtifactIds="$include_ids" \
    $(scope_args) \
    -Dmdep.useRepositoryLayout=true \
    -Dmdep.failOnMissingClassifierArtifact=false \
    -DoutputDirectory="$staging" 2>&1 | grep -viE 'download(ing|ed)|^\[INFO\]' || true)

# ---------------------------------------------------------------------------------------------
# Step 3: move into the kvasir layout. Sources are immutable per version, so an already present
# JAR is left alone - kvasir would skip it by fingerprint anyway, but this keeps the run quiet.
# ---------------------------------------------------------------------------------------------
echo "==> Writing into $DATA_DIR"
imported=0
missing=0
while IFS= read -r gav; do
    artifact="${gav#*:}"; artifact="${artifact%:*}"
    version="${gav##*:}"
    jar="$(find "$staging" -type f -name "${artifact}-${version}-sources.jar" 2>/dev/null | head -1)"
    if [[ -z "$jar" ]]; then
        echo "    -- $artifact:$version: no sources JAR published"
        missing=$((missing + 1))
        continue
    fi
    target="$DATA_DIR/$artifact/$version"
    mkdir -p "$target"
    if [[ -f "$target/$(basename "$jar")" ]]; then
        echo "    == $artifact/$version (already present)"
    else
        cp "$jar" "$target/"
        echo "    ++ $artifact/$version"
        imported=$((imported + 1))
    fi
done < "$wanted"

echo "==> $imported new, $missing without sources"

# ---------------------------------------------------------------------------------------------
# Step 4: optionally trigger the scan. Indexing is an admin operation over REST, deliberately not
# an MCP tool - see CLAUDE.md.
# ---------------------------------------------------------------------------------------------
if [[ -n "$INDEX_URL" && "$imported" -gt 0 ]]; then
    echo "==> Triggering scan at $INDEX_URL/admin/index"
    curl -fsS -X POST "$INDEX_URL/admin/index" && echo
elif [[ -n "$INDEX_URL" ]]; then
    echo "==> Nothing new, skipping the scan"
else
    echo "==> Now index it:  curl -X POST http://localhost:8080/admin/index"
fi
