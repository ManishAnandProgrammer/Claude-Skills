#!/usr/bin/env bash
# Verifies the skills repository before a push (it is private: there is no CI):
#   1. reference-app builds and every test passes (Testcontainers: Docker must be reachable);
#   2. bootstrap-copy.sh copies core + sign-in into a fresh project under another package, and that
#      project builds and passes its tests too.
# Run it by hand, or let .githooks/pre-push run it. Exit code 0 only when both are green.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
REFERENCE="$REPO/reference-app"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/claude-skills-verify.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

mvn_verify() {
    local dir="$1" log="$2"
    if (cd "$dir" && ./mvnw -B verify > "$log" 2>&1); then
        grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" "$log" | tail -1
        return 0
    fi
    echo "FAILED: $dir (log: $log)" >&2
    grep -E "Tests run:.*FAIL|\[ERROR\]   |COMPILATION ERROR|Could not find a valid Docker" "$log" | head -20 >&2
    cp "$log" "${TMPDIR:-/tmp}/claude-skills-verify-failed.log"
    echo "full log kept at ${TMPDIR:-/tmp}/claude-skills-verify-failed.log" >&2
    return 1
}

docker info > /dev/null 2>&1 || {
    echo "Docker is not reachable: the tests need it (Testcontainers). Start Docker, or run this as 'sg docker -c $0'." >&2
    exit 1
}

echo "[1/2] reference-app: build and tests"
mvn_verify "$REFERENCE" "$WORK/reference.log"

echo "[2/2] bootstrap-copy into a fresh project (package com.acme.smoke)"
project="$WORK/smoke"
mkdir -p "$project/src/main/java/com/acme/smoke"
cp -r "$REFERENCE/mvnw" "$REFERENCE/.mvn" "$project/"
sed -e 's|<artifactId>reference-app</artifactId>|<artifactId>smoke</artifactId>|' -e 's|<name>reference-app</name>|<name>smoke</name>|' \
    "$REFERENCE/pom.xml" > "$project/pom.xml"
cat > "$project/src/main/java/com/acme/smoke/SmokeApplication.java" <<'JAVA'
package com.acme.smoke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SmokeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmokeApplication.class, args);
    }
}
JAVA
"$REPO/scripts/bootstrap-copy.sh" --target "$project" --package com.acme.smoke --with core,sign-in > "$WORK/copy.log"
grep -q "com.example.app" -r "$project/src" && { echo "FAILED: com.example.app left in the copied sources" >&2; exit 1; }
mvn_verify "$project" "$WORK/smoke.log"

echo "verified: reference-app and a bootstrap copy both build green"
