#!/usr/bin/env bash
#
# Runs Kvasir with the contents of .env in the process environment.
#
# Not needed for an ordinary start: Quarkus reads .env by itself, and application.properties maps
# the credentials from there onto the system properties the S3 filesystem reads. Starting from an
# IDE therefore needs nothing of this.
#
# It is needed where something calls System.getenv() instead, because no framework can add to the
# environment of a JVM that is already running - Java has no API for it. That is the case for
# S3DataStoreTest ("test" below), and for a packaged run started outside the project directory,
# where .env is not in the working directory to begin with.
#
#     scripts/run-with-env.sh                 # dev mode (default)
#     scripts/run-with-env.sh jar             # the packaged application
#     scripts/run-with-env.sh test            # the build, including the S3 tests
#
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
    echo "No .env found. Copy .env.example to .env and fill in your credentials." >&2
    exit 1
fi

# -a exports every variable set from here on, which is what turns the assignments in .env into
# environment variables rather than plain shell variables the child process would never see.
set -a
# shellcheck disable=SC1091
source .env
set +a

case "${1:-dev}" in
    dev)
        exec ./mvnw quarkus:dev
        ;;
    jar)
        [[ -f target/quarkus-app/quarkus-run.jar ]] || ./mvnw package -DskipTests
        exec java -jar target/quarkus-app/quarkus-run.jar
        ;;
    test)
        exec ./mvnw test
        ;;
    *)
        echo "Usage: $0 [dev|jar|test]" >&2
        exit 2
        ;;
esac
