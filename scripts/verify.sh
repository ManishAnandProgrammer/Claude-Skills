#!/usr/bin/env bash
# Verifies the skills repository before a push (it is private: there is no CI):
#   1. reference-app builds and every test passes (Testcontainers: Docker must be reachable);
#   2. bootstrap-copy.sh copies core + sign-in into a fresh project under another package and
#      another application name, and that project builds and passes its tests too;
#      2b. the same with the example module and the metadata piece, so every golden file arrives
#          with its changesets;
#      2c. core + sign-in + metadata without the example: the mechanism alone, none of its samples;
#   3. the reference's Docker image starts with the prod profile against PostgreSQL and Redis:
#      healthy, sign-in rate limited through Redis, metrics only on the management port. Tests can't
#      see this: Testcontainers hands them their connections (a missing Redis setting once switched
#      rate limiting off in every deployment, with every test green).
# Run it by hand, or let .githooks/pre-push run it. Exit code 0 only when all three are green.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/fingerprint.sh
. "$REPO/scripts/lib/fingerprint.sh"
# Taken before the run: it names what is being tested, even if files change meanwhile.
fingerprint="$(working_fingerprint "$REPO")"
REFERENCE="$REPO/reference-app"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/claude-skills-verify.XXXXXX")"
RUN="claude-skills-verify-$$"
cleanup() {
    docker rm -f "$RUN-app" "$RUN-pg" "$RUN-redis" > /dev/null 2>&1 || true
    docker network rm "$RUN-net" > /dev/null 2>&1 || true
    docker image rm "$RUN-image" > /dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

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

echo "[1/3] reference-app: build and tests"
mvn_verify "$REFERENCE" "$WORK/reference.log"

echo "[2/3] bootstrap-copy into a fresh project (package com.acme.smoke)"
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
grep -rqE "example:(begin|end)|TODO_LIST|todo-list" "$project/src" && { echo "FAILED: example-module entries left in a copy without the example piece" >&2; exit 1; }
grep -rqE "metadata:(begin|end)|METADATA_|configuration_metadata|MetadataKey" "$project/src" && { echo "FAILED: metadata-piece entries left in a copy without the metadata piece" >&2; exit 1; }
{ [ -d "$project/src/main/java/com/acme/smoke/metadata" ] || grep -q "ADMIN" "$project/src/main/java/com/acme/smoke/user/domain/Role.java"; } \
    && { echo "FAILED: the metadata module or its ADMIN role in a copy without the metadata piece" >&2; exit 1; }
# Bootstrap renames the application; tests must not depend on the reference's name.
sed -i 's/\${APP_NAME:reference}/${APP_NAME:smoke}/' "$project/src/main/resources/application.yml"
grep -q 'APP_NAME:smoke' "$project/src/main/resources/application.yml" || { echo "FAILED: could not rename the smoke application" >&2; exit 1; }
mvn_verify "$project" "$WORK/smoke.log"

echo "[2b/3] bootstrap-copy with the example module (package com.acme.example)"
example="$WORK/example"
mkdir -p "$example/src/main/java/com/acme/example"
cp -r "$REFERENCE/mvnw" "$REFERENCE/.mvn" "$example/"
sed -e 's|<artifactId>reference-app</artifactId>|<artifactId>example</artifactId>|' -e 's|<name>reference-app</name>|<name>example</name>|' \
    "$REFERENCE/pom.xml" > "$example/pom.xml"
sed -e 's/package com.acme.smoke;/package com.acme.example;/' -e 's/SmokeApplication/ExampleApplication/g' \
    "$project/src/main/java/com/acme/smoke/SmokeApplication.java" > "$example/src/main/java/com/acme/example/ExampleApplication.java"
"$REPO/scripts/bootstrap-copy.sh" --target "$example" --package com.acme.example --with core,sign-in,example,metadata > "$WORK/copy-example.log"
grep -rqE "(example|metadata):(begin|end)" "$example/src" && { echo "FAILED: piece markers left in the copy" >&2; exit 1; }
[ -f "$example/src/test/java/com/acme/example/metadata/MetadataIntegrationTest.java" ] || { echo "FAILED: the metadata samples' tests are missing from the copy with both pieces" >&2; exit 1; }
mvn_verify "$example" "$WORK/example.log"

echo "[2c/3] bootstrap-copy with the metadata piece, without the example (package com.acme.meta)"
meta="$WORK/meta"
mkdir -p "$meta/src/main/java/com/acme/meta"
cp -r "$REFERENCE/mvnw" "$REFERENCE/.mvn" "$meta/"
sed -e 's|<artifactId>reference-app</artifactId>|<artifactId>meta</artifactId>|' -e 's|<name>reference-app</name>|<name>meta</name>|' \
    "$REFERENCE/pom.xml" > "$meta/pom.xml"
sed -e 's/package com.acme.smoke;/package com.acme.meta;/' -e 's/SmokeApplication/MetaApplication/g' \
    "$project/src/main/java/com/acme/smoke/SmokeApplication.java" > "$meta/src/main/java/com/acme/meta/MetaApplication.java"
"$REPO/scripts/bootstrap-copy.sh" --target "$meta" --package com.acme.meta --with core,sign-in,metadata > "$WORK/copy-meta.log"
grep -rqE "(example|metadata):(begin|end)|TODO_LIST|todo-list|ATTENDANCE|AttendanceConfig" "$meta/src" && { echo "FAILED: samples or markers left in a metadata copy without the example piece" >&2; exit 1; }
[ -f "$meta/src/main/resources/db/changelog/changes/0007-users-admin-role.sql" ] || { echo "FAILED: the ADMIN role changeset is missing from the metadata copy" >&2; exit 1; }
mvn_verify "$meta" "$WORK/meta.log"

echo "[3/3] reference-app image: prod profile against PostgreSQL and Redis"
docker build -q -t "$RUN-image" "$REFERENCE" > /dev/null
docker network create "$RUN-net" > /dev/null
docker run -d --name "$RUN-pg" --network "$RUN-net" -e POSTGRES_DB=reference -e POSTGRES_USER=verify \
    -e POSTGRES_PASSWORD=verify-password postgres:18 > /dev/null
docker run -d --name "$RUN-redis" --network "$RUN-net" redis:8 > /dev/null
docker run -d --name "$RUN-app" --network "$RUN-net" -m 768m \
    -e SPRING_PROFILES_ACTIVE=prod -e DB_HOST="$RUN-pg" -e DB_NAME=reference -e DB_USER=verify -e DB_PASSWORD=verify-password \
    -e REDIS_HOST="$RUN-redis" -e JWT_SECRET="$(head -c 48 /dev/urandom | base64)" \
    -e PERSONAL_DATA_PEPPER="$(head -c 48 /dev/urandom | base64)" -e BREACHED_PASSWORD_CHECK_ENABLED=false \
    "$RUN-image" > /dev/null
status=""
for _ in $(seq 1 60); do
    status="$(docker inspect -f '{{.State.Health.Status}}' "$RUN-app" 2>/dev/null || echo gone)"
    [ "$status" = healthy ] || [ "$status" = gone ] && break
    sleep 3
done
if [ "$status" != healthy ]; then
    echo "FAILED: the container is $status" >&2
    docker logs "$RUN-app" 2>&1 | tail -30 >&2
    exit 1
fi
in_app() { docker exec "$RUN-app" sh -c "$1"; }
login='curl -s -o /dev/null -w "%{http_code} " -H Content-Type:application/json -d "{\"email\":\"verify@example.com\",\"password\":\"Wrong-Pass-1\"}" localhost:8080/api/v1/auth/login'
codes="$(in_app "for i in 1 2 3 4 5 6 7 8; do $login; done")"
[[ "$codes" == *429* ]] || { echo "FAILED: no 429 after repeated logins (rate limiting is off: is Redis reached?): $codes" >&2; exit 1; }
[ "$(in_app 'curl -s -o /dev/null -w "%{http_code}" localhost:8080/actuator/prometheus')" = 404 ] \
    || { echo "FAILED: /actuator/prometheus answers on the public port" >&2; exit 1; }
in_app 'curl -sf localhost:8081/actuator/prometheus' | grep -q '^rate_limit' \
    || { echo "FAILED: no rate-limit metrics on the management port" >&2; exit 1; }
echo "container: healthy, rate limited through Redis ($codes), metrics on the management port only"

if [ "$(working_fingerprint "$REPO")" = "$fingerprint" ]; then
    echo "$fingerprint" > "$(verified_record "$REPO")"
    echo "recorded: a push of exactly this code passes the pre-push check without running this again"
else
    echo "files changed during the run: nothing recorded, run it again before pushing" >&2
fi
echo "verified: reference-app, a bootstrap copy and the image are green"
