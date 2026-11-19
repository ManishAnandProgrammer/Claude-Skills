#!/usr/bin/env bash
# Verifies the skills repository before a push (it is private: there is no CI):
#   0. the overlays' replacement files were written against the reference's current files
#      (scripts/overlay-replacements.sh --check);
#   1. reference-app builds and every test passes (Testcontainers: Docker must be reachable), and so do its
#      quality gates: static analysis with security rules and the coverage minimum (verify, house-review CFG-11).
#      Every copy below runs the same gates from the same pom and its piece's exclude entries (an overlay's
#      own with tenancy), with coverage minimums of its own; 2d and 2f, which run a subset of the tests, check
#      static analysis only. It is a plain application: tenancy exists only in overlays/,
#      so it is proven by 2g and 2h;
#   2. bootstrap-copy.sh copies core + sign-in into a fresh project under another package and
#      another application name, and that project builds and passes its tests too;
#      2b. the same with the example module and the metadata piece, so every golden file arrives
#          with its changesets;
#      2c. core + sign-in + metadata without the example: the mechanism alone, none of its samples;
#      2e. core + sign-in + media without the example: uploads, policies and jobs alone (no purpose,
#          no todo attachments); its integration tests run against an S3-compatible store, and the
#          upload endpoint tests arrive skipped (they need a purpose);
#      2f. core + sign-in + example + media + session-version: the todo attachments arrive with both pieces
#          (the attachment, upload and policy tests run against TODO_ATTACHMENT; the rest is 2b's and 1's),
#          the session version arrives per account without tenancy (its tests run here), and the
#          dependency lists the script prints cover the reference pom;
#      2g. core + sign-in + tenancy-schema: schema per tenant alone, staff-only registration (no tenant
#          table but the tenant's members and the tests' probe), with real database roles; every test runs;
#      2h. every piece: core + sign-in + example + metadata + media + tenancy-schema +
#          tenant-registration + session-version + change-history. The todo, plan, metadata and media tables in the tenant
#          changelog, the media keys per tenant, the cross-tenant tests, self-registration, the session
#          version per membership (a platform changeset); every test runs, the reference's own tests
#          inside the test tenant;
#      2i. core + sign-in + tenancy-row: row mode alone, staff-only registration (no tenant table but
#          the tenant's members and the tests' probe), with real database roles (the app role
#          NOBYPASSRLS): the hook, the policies' guard, the isolation tests; every test runs;
#      2j. every piece with row mode: core + sign-in + example + metadata + media + tenancy-row +
#          tenant-registration + session-version + change-history. Every reference table a tenant table under its forced
#          policy (the catalogue guard), composite keys, seeds on TenantProvisioned, the cross-tenant
#          tests, the session version per membership; every test runs, the reference's own tests inside
#          the test tenant;
#      2k, 2l, 2m. every piece but the example, as a project starts: without tenancy (2k), schema per tenant
#          (2l), row mode (2m): the pieces' own code and tests with none of the example's samples to lean on;
#          every test runs;
#      2n. every piece with the roles piece, without tenancy: core + sign-in + example + metadata + media +
#          session-version + change-history + roles (Level 2). Every Level 1 test runs unchanged (but those that
#          asserted the role column or claim: roles:else blocks), with the roles piece's own: the tables, the per-request read, the
#          endpoints and the safety rules; the upgrade path copies each seeded account's role into an assignment;
#      2o, 2p. the same with row mode (2o) and schema per tenant (2p), tenant-registration included: the roles are each
#          tenant's, a person's access is on their member row, adding and removing a member are access changes under
#          the safety rules counted per tenant, staff's suspension counts every tenant of the person; the upgrade path
#          copies each seeded membership's role (row mode in one changeset, schema per tenant per schema, then the
#          platform's column dropped once every schema copied: expand, contract);
#      the session version is opt-in: every copy without it (2, 2b, 2c, 2e, 2g, 2i, 2d) is checked for
#      none of its traces (no column, claim, filter, endpoint or configuration key); so is the change history
#      (the reference, 2h, 2j, 2k, 2l, 2m, 2n, 2o and 2p have it; 2, 2b, 2c, 2e, 2f, 2g, 2i and 2d have none of its
#      traces: no table, trigger function, actor, job or setting); and so is the roles piece (only 2n, 2o and 2p have it);
#      2d. core alone (a project signing users in elsewhere), without sign-in's Redis and Bucket4j
#          dependencies: main and test code compile and the unit tests pass. Its integration and
#          ArchUnit tests are left out: the integration tests sign users in through sign-in, and the
#          ArchUnit rules fail on a project with no module yet;
#   the stages with the example (1, 2b, 2h, 2j) run on an upgraded database: Liquibase applies the previous
#   release's changesets alone, a seed fills them as a used database holds them (tenants in every status,
#   skewed data, a member who left), then the application's own run applies the newer changesets over it
#   (support/PreviousReleaseDatabase); UpgradeFromPreviousReleaseIntegrationTest checks what they did, and
#   every other test runs on that database too. Backfills never run only on empty tables again;
#   3. the reference's Docker image starts with the prod profile against PostgreSQL and Redis:
#      healthy, sign-in rate limited through Redis, metrics only on the management port. Tests can't
#      see this: Testcontainers hands them their connections (a missing Redis setting once switched
#      rate limiting off in every deployment, with every test green).
# Run it by hand before a push (.githooks/pre-push checks that it passed on exactly this code).
# Exit code 0 only when all three are green.
#
# While iterating, --only runs just the named stages (comma list of the ids below); step 0 and the
# setup the copies share always run:
#   scripts/verify.sh --only 2i,2j
#   ids: reference (1), 2, 2b, 2c, 2e, 2f, 2g, 2h, 2i, 2j, 2k, 2l, 2m, 2n, 2o, 2p, 2d, image (3)
# A partial run never records the fingerprint, so a push still needs one full run.
set -euo pipefail

STAGES="reference 2 2b 2c 2e 2f 2g 2h 2i 2j 2k 2l 2m 2n 2o 2p 2d image"
# The highest changeset number of the release in production (main): the upgrade path starts from it.
# Raise it, and extend the previous-release seeds, when a release ships. The pieces' range alone (0001-0099):
# the reference has no project changesets (a project passes <pieces>,<project>, PreviousReleaseDatabase).
PREVIOUS_RELEASE=32
UPGRADE="-Dapp.test.upgrade.previous-release=$PREVIOUS_RELEASE"
only=""
case "${1:-}" in
    "") ;;
    --only)
        [ $# -eq 2 ] || { echo "usage: $0 [--only <stage,stage,...>]" >&2; exit 2; }
        only="$2"
        for stage in ${only//,/ }; do
            [[ " $STAGES " == *" $stage "* ]] || { echo "unknown stage '$stage'; the stages are: $STAGES" >&2; exit 2; }
        done
        ;;
    *) echo "usage: $0 [--only <stage,stage,...>]   (stages: $STAGES)" >&2; exit 2 ;;
esac

# Without --only every stage runs.
wants() {
    [ -z "$only" ] || [[ ",$only," == *",$1,"* ]]
}

# What the session-version piece leaves in a copy; a copy without it has none of it, its prose included (an API
# text or javadoc describing the mechanism to a deployment that doesn't have it).
SESSION_VERSION_TRACES='session-version|SessionVersion|session_version|SESSION_VERSION|"sv"|membership_id|TenantDeactivated|users/me/sessions|endAllSessions|logoutEverywhere|[Ss]ession versions?'
no_session_version() {
    if grep -rqE "$SESSION_VERSION_TRACES" "$@"; then
        echo "FAILED: session-version entries in a copy without the session-version piece:" >&2
        grep -rlE "$SESSION_VERSION_TRACES" "$@" | head -5 >&2
        exit 1
    fi
}
# What the change-history piece leaves in a copy (its table, function, actor, job and settings); a copy without it has
# none of it.
CHANGE_HISTORY_TRACES='change-history|ChangeHistory|audit_log|audit_row_change|app\.user_id|[Cc]hange history'
# What the roles piece leaves in a copy (its tables, entities, endpoints, error codes and permissions); a copy without it
# has none of it, and keeps Level 1 as it is.
ROLES_TRACES='roles:(begin|else|end)|AccessRole|RoleAssignment|PersonalGrant|PersonAccess|TenantMemberManager|TenantAccess|user_roles|user_permissions|role_permissions|ROLES_MANAGE|ACCESS_GRANT|ACCESS_LAST_MANAGER|ACCESS_NOT_HELD|PermissionsUnreadable|memberships-role|[Rr]oles piece'
no_roles() {
    if grep -rqE "$ROLES_TRACES" "$@"; then
        echo "FAILED: roles entries in a copy without the roles piece:" >&2
        grep -rlE "$ROLES_TRACES" "$@" | head -5 >&2
        exit 1
    fi
}
no_change_history() {
    if grep -rqE "$CHANGE_HISTORY_TRACES" "$@"; then
        echo "FAILED: change-history entries in a copy without the change-history piece:" >&2
        grep -rlE "$CHANGE_HISTORY_TRACES" "$@" | head -5 >&2
        exit 1
    fi
}

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

# The whole suite's line and branch coverage from JaCoCo's report, when the stage measured it.
coverage_of() {
    local csv="$1/target/site/jacoco/jacoco.csv"
    [ -f "$csv" ] || return 0
    awk -F, 'NR > 1 { bm += $6; bc += $7; lm += $8; lc += $9 }
        END { printf "coverage: lines %.2f %%, branches %.2f %%\n", 100 * lc / (lc + lm), 100 * bc / (bc + bm) }' "$csv"
}

# verify runs the tests and the quality gates (static analysis, the coverage minimum). Extra arguments go to Maven.
# A copy holds fewer tests than the reference, so each copy stage passes minimums of its own
# (-Dcoverage.line.minimum, -Dcoverage.branch.minimum): measured and set like the reference's (house-review
# CFG-11: a full point below the lowest measured run, rounded down), and only ever raised.
mvn_verify() {
    local dir="$1" log="$2"
    shift 2
    if (cd "$dir" && ./mvnw -B verify "$@" > "$log" 2>&1); then
        grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" "$log" | tail -1
        coverage_of "$dir"
        return 0
    fi
    echo "FAILED: $dir (log: $log)" >&2
    grep -E "Tests run:.*FAIL|\[ERROR\]   |\[ERROR\] (High|Medium):|Rule violated|COMPILATION ERROR|Could not find a valid Docker" "$log" | head -20 >&2 \
        || true  # no line matched: under set -e the log below must still be kept
    cp "$log" "${TMPDIR:-/tmp}/claude-skills-verify-failed.log"
    echo "full log kept at ${TMPDIR:-/tmp}/claude-skills-verify-failed.log" >&2
    return 1
}

# The upgrade path ran in this project: its test class ran every test, none skipped.
upgraded() {
    local report
    report="$(find "$1/target/surefire-reports" -name 'TEST-*UpgradeFromPreviousReleaseIntegrationTest.xml' | head -1)"
    [ -n "$report" ] && grep -qE 'tests="[1-9][0-9]*"' "$report" && grep -q 'skipped="0"' "$report" \
        || { echo "FAILED: the upgrade path didn't run in $1 (UpgradeFromPreviousReleaseIntegrationTest missing or skipped)" >&2; exit 1; }
}

docker info > /dev/null 2>&1 || {
    echo "Docker is not reachable: the tests need it (Testcontainers). Start Docker, or run this as 'sg docker -c $0'." >&2
    exit 1
}

echo "[0/3] overlays: replacements pinned to the reference's files"
"$REPO/scripts/overlay-replacements.sh" --check
# The roles piece is an overlay too: the reference runs Level 1, and a roles block's own lines are comments in it.
grep -rnE "AccessRole|PersonAccessService|RoleAssignment|Permission\.(ROLES_MANAGE|ACCESS_GRANT)" "$REFERENCE/src/main/java" | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*|<!--)' \
    && { echo "FAILED: roles code in the reference outside a commented roles block (it belongs in overlays/roles)" >&2; exit 1; }
# A commented line may name them: a tenancy block's own lines are comments in the reference.
grep -rnE "TenantContext|TenantScope|tenant_memberships" "$REFERENCE/src/main/java" | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*)' \
    && { echo "FAILED: tenancy code in the reference outside a commented tenancy block (it belongs in overlays/)" >&2; exit 1; }

# The static-analysis exclude file holds false positives one class and bug pattern at a time (house-review CFG-11):
# never a package, a whole category, a priority or a rank, so a new finding of the same kind still fails the build.
for exclude in $(find "$REFERENCE" "$REPO/overlays" -name spotbugs-exclude.xml -not -path '*/target/*'); do
    perl -0777 -ne 'while (/<Match>(.*?)<\/Match>/sg) { my $m = $1;
            exit 1 unless $m =~ /<Class name=/ && $m =~ /<Bug pattern=/ && $m !~ /<(Package|Priority|Rank)\b|category=/ }' "$exclude" \
        || { echo "FAILED: a <Match> in $exclude without a class and a bug pattern, or with a package, category, priority or rank" >&2; exit 1; }
done

# Generators and reviewers read the skill texts, the guides, the design notes and the reference: they say why
# in general terms, never which field test found what or when (house-review/DESIGN.md, the design log, is exempt).
grep -rnE "(help|campus|library)-desk|\b[Rr]ound [0-9]" "$REPO/crud-gen" "$REPO/house-review" "$REPO/overlays" \
        "$REPO/docs" "$REPO/guides" "$REFERENCE/src" "$REFERENCE/docs" "$REFERENCE/README.md" --exclude=DESIGN.md \
    && { echo "FAILED: field-test history (a project name or a round number) in the skill texts, guides, docs or the reference" >&2; exit 1; }
# No rule sends its reader into a design note: a log holds old and current decisions side by side.
grep -rn "docs/design" "$REPO/crud-gen" "$REPO/house-review/SKILL.md" "$REPO/house-review/references" "$REPO/guides" \
    && { echo "FAILED: a skill text or guide cites docs/design (a history log, not a rule)" >&2; exit 1; }

# The profile templates agree with the rules: every {{key}} a skill's text uses is a row of its own template; a key
# both templates have has one default (where both give a value, not a format); and no default is a value a rule
# grades as a finding (NOT_A_DEFAULT: key=value, the rule that finds it).
NOT_A_DEFAULT="NotXss=none:REST-10"
template_default() {
    # A row may name two keys (`migrationsDir` / `changesetAuthor`): the key in its first cell.
    awk -F'|' -v key="\`$2\`" 'index($2, key) { gsub(/^ +| +$/, "", $3); print $3; exit }' "$REPO/$1/profile-template.md"
}
for skill in crud-gen house-review; do
    for key in $(grep -rhoE '\{\{[A-Za-z]+\}\}' "$REPO/$skill" --include=*.md | tr -d '{}' | sort -u); do
        [ "$key" = key ] && continue
        [ -n "$(template_default "$skill" "$key")" ] \
            || { echo "FAILED: $skill's texts use {{$key}}, which its profile-template.md has no row for" >&2; exit 1; }
    done
done
for key in $(grep -oE '^\| `[A-Za-z]+` \|' "$REPO/crud-gen/profile-template.md" | tr -d '|` '); do
    crud="$(template_default crud-gen "$key")" review="$(template_default house-review "$key")"
    if [[ "$crud" == \`* && "$review" == \`* && "$crud" != "$review" ]]; then
        echo "FAILED: the profile templates give $key two defaults: crud-gen $crud, house-review $review" >&2
        exit 1
    fi
done
for entry in $NOT_A_DEFAULT; do
    key="${entry%%=*}" rest="${entry#*=}" value="${rest%%:*}" rule="${rest#*:}"
    for skill in crud-gen house-review; do
        if [ "$(template_default "$skill" "$key")" = "\`$value\`" ]; then
            echo "FAILED: $skill's profile template defaults $key to $value, which $rule finds" >&2
            exit 1
        fi
    done
done

# --render shows a golden file as a mode's project gets it: row mode's tenant lines live, no marker left, and
# none of them without tenancy.
golden_todos="src/main/resources/db/changelog/changes/0003-todos.sql"
row_todos="$("$REPO/scripts/bootstrap-copy.sh" --render row "$golden_todos")"
plain_todos="$("$REPO/scripts/bootstrap-copy.sh" --render none "$golden_todos")"
if ! grep -q "^CREATE POLICY tenant_isolation ON todos" <<< "$row_todos" || grep -qE ":(begin|else|end)" <<< "$row_todos$plain_todos" \
        || grep -q "tenant_id" <<< "$plain_todos"; then
    echo "FAILED: bootstrap-copy.sh --render doesn't show 0003 as row mode and a plain project read it" >&2
    exit 1
fi

if wants reference; then
    echo "[1/3] reference-app: build and tests"
    mvn_verify "$REFERENCE" "$WORK/reference.log" "$UPGRADE"
    upgraded "$REFERENCE"
fi

# The smoke project's application class is the template every other copy starts from.
project="$WORK/smoke"
mkdir -p "$project/src/main/java/com/acme/smoke"
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

if wants 2; then
    echo "[2/3] bootstrap-copy into a fresh project (package com.acme.smoke)"
    cp -r "$REFERENCE/mvnw" "$REFERENCE/.mvn" "$project/"
    sed -e 's|<artifactId>reference-app</artifactId>|<artifactId>smoke</artifactId>|' -e 's|<name>reference-app</name>|<name>smoke</name>|' \
        "$REFERENCE/pom.xml" > "$project/pom.xml"
    "$REPO/scripts/bootstrap-copy.sh" --target "$project" --package com.acme.smoke --with core,sign-in > "$WORK/copy.log"
    grep -q "com.example.app" -r "$project/src" && { echo "FAILED: com.example.app left in the copied sources" >&2; exit 1; }
    [ -f "$project/docs/runbook.md" ] || { echo "FAILED: the incident runbook is missing from the core copy" >&2; exit 1; }
    grep -qiwE "todo|todos" "$project/compose.yaml" "$project/.env.example" && { echo "FAILED: the example's names left in compose.yaml or .env.example" >&2; exit 1; }
    grep -rq "docs/design" "$project/src" && { echo "FAILED: a copied file points to a design note that isn't copied" >&2; exit 1; }
    grep -rqE "sign-in:(begin|end)" "$project/src" "$project/compose.yaml" "$project/.env.example" && { echo "FAILED: sign-in markers left in the copy" >&2; exit 1; }
    grep -rqE "example:(begin|end)|TODO_LIST|todo-list" "$project/src" && { echo "FAILED: example-module entries left in a copy without the example piece" >&2; exit 1; }
    grep -rqE "metadata:(begin|end)|METADATA_|configuration_metadata|MetadataKey" "$project/src" && { echo "FAILED: metadata-piece entries left in a copy without the metadata piece" >&2; exit 1; }
    grep -rqE "media:(begin|end)|admin:(begin|end)|MEDIA_(BUCKET|STORAGE)|UPLOAD_|media_files|upload_policies|UploadPurpose|awssdk" "$project/src" "$project/.env.example" \
        && { echo "FAILED: media-piece or admin entries left in a copy without the media piece" >&2; exit 1; }
    # No tenancy without its piece, and every ":else" part arrived uncommented (the build proves it compiles).
    grep -rqE "tenancy(-schema|-row)?:(begin|else|end)|TenantContext|TenantScope|TenantOwnedEntity|tenant_memberships|app\.tenant_id|ROW LEVEL SECURITY|DB_OWNER_USER|search_path" "$project/src" "$project/.env.example" \
        && { echo "FAILED: tenancy entries left in a copy without a tenancy piece" >&2; exit 1; }
    [ -f "$project/src/main/java/com/acme/smoke/config/LazyConnectionConfig.java" ] && [ -f "$project/src/main/resources/db/changelog/db.changelog-master.yaml" ] \
        || { echo "FAILED: the lazy connection proxy or the single Liquibase master is missing from a copy without tenancy" >&2; exit 1; }
    [ -d "$project/src/main/java/com/acme/smoke/metadata" ] && { echo "FAILED: the metadata module in a copy without the metadata piece" >&2; exit 1; }
    # Sign-in alone brings the ADMIN role: an ADMIN suspends and reactivates accounts.
    grep -q "ADMIN" "$project/src/main/java/com/acme/smoke/user/domain/Role.java" \
        && grep -q '"/{id}/status"' "$project/src/main/java/com/acme/smoke/user/controller/UserController.java" \
        && [ -f "$project/src/main/resources/db/changelog/changes/0007-users-admin-role.sql" ] \
        || { echo "FAILED: the ADMIN role or the account suspension endpoint is missing from the sign-in copy" >&2; exit 1; }
    no_session_version "$project/src" "$project/docs" "$project/compose.yaml" "$project/.env.example"
    no_change_history "$project/src" "$project/docs" "$project/compose.yaml" "$project/.env.example"
    no_roles "$project/src" "$project/docs" "$project/spotbugs-exclude.xml" "$project/compose.yaml" "$project/.env.example"
    # Bootstrap renames the application; tests must not depend on the reference's name.
    sed -i 's/\${APP_NAME:reference}/${APP_NAME:smoke}/' "$project/src/main/resources/application.yml"
    grep -q 'APP_NAME:smoke' "$project/src/main/resources/application.yml" || { echo "FAILED: could not rename the smoke application" >&2; exit 1; }
    # Coverage measured 2026-11-18 at lines 89.33 %, branches 76.52 %; only ever raised.
    mvn_verify "$project" "$WORK/smoke.log" -Dcoverage.line.minimum=0.88 -Dcoverage.branch.minimum=0.75
fi

if wants 2b; then
    echo "[2b/3] bootstrap-copy with the example module (package com.acme.example)"
    example="$WORK/example"
    mkdir -p "$example/src/main/java/com/acme/example"
    cp -r "$REFERENCE/mvnw" "$REFERENCE/.mvn" "$example/"
    sed -e 's|<artifactId>reference-app</artifactId>|<artifactId>example</artifactId>|' -e 's|<name>reference-app</name>|<name>example</name>|' \
        "$REFERENCE/pom.xml" > "$example/pom.xml"
    sed -e 's/package com.acme.smoke;/package com.acme.example;/' -e 's/SmokeApplication/ExampleApplication/g' \
        "$project/src/main/java/com/acme/smoke/SmokeApplication.java" > "$example/src/main/java/com/acme/example/ExampleApplication.java"
    "$REPO/scripts/bootstrap-copy.sh" --target "$example" --package com.acme.example --with core,sign-in,example,metadata > "$WORK/copy-example.log"
    grep -rqE "(example|metadata|admin|media|tenancy-row):(begin|else|end)|TenantOwnedEntity|app\.tenant_id|ROW LEVEL SECURITY|definePolicy" "$example/src" && { echo "FAILED: piece markers or row-mode tenancy left in the copy" >&2; exit 1; }
    grep -rqE "Attachment|UploadPurpose|MEDIA_(BUCKET|STORAGE)|UPLOAD_|media_files|awssdk" "$example/src" && { echo "FAILED: media entries left in an example copy without the media piece" >&2; exit 1; }
    [ -f "$example/src/test/java/com/acme/example/metadata/MetadataIntegrationTest.java" ] || { echo "FAILED: the metadata samples' tests are missing from the copy with both pieces" >&2; exit 1; }
    no_session_version "$example/src" "$example/docs"
    no_change_history "$example/src" "$example/docs"
    no_roles "$example/src" "$example/docs" "$example/spotbugs-exclude.xml"
    # Coverage measured 2026-11-18 at lines 95.64 %, branches 84.76 %; only ever raised.
    mvn_verify "$example" "$WORK/example.log" "$UPGRADE" -Dcoverage.line.minimum=0.94 -Dcoverage.branch.minimum=0.83
    upgraded "$example"
fi

if wants 2c; then
    echo "[2c/3] bootstrap-copy with the metadata piece, without the example (package com.acme.meta)"
    meta="$WORK/meta"
    mkdir -p "$meta/src/main/java/com/acme/meta"
    cp -r "$REFERENCE/mvnw" "$REFERENCE/.mvn" "$meta/"
    sed -e 's|<artifactId>reference-app</artifactId>|<artifactId>meta</artifactId>|' -e 's|<name>reference-app</name>|<name>meta</name>|' \
        "$REFERENCE/pom.xml" > "$meta/pom.xml"
    sed -e 's/package com.acme.smoke;/package com.acme.meta;/' -e 's/SmokeApplication/MetaApplication/g' \
        "$project/src/main/java/com/acme/smoke/SmokeApplication.java" > "$meta/src/main/java/com/acme/meta/MetaApplication.java"
    "$REPO/scripts/bootstrap-copy.sh" --target "$meta" --package com.acme.meta --with core,sign-in,metadata > "$WORK/copy-meta.log"
    grep -rqE "(example|metadata):(begin|end)|TODO_LIST|todo-list|TodoListSettings" "$meta/src" && { echo "FAILED: samples or markers left in a metadata copy without the example piece" >&2; exit 1; }
    [ -f "$meta/src/main/resources/db/changelog/changes/0007-users-admin-role.sql" ] || { echo "FAILED: the ADMIN role changeset is missing from the metadata copy" >&2; exit 1; }
    no_session_version "$meta/src" "$meta/docs"
    no_change_history "$meta/src" "$meta/docs"
    no_roles "$meta/src" "$meta/docs" "$meta/spotbugs-exclude.xml"
    # Coverage measured 2026-11-18 at lines 84.73 %, branches 74.80 %; only ever raised.
    mvn_verify "$meta" "$WORK/meta.log" -Dcoverage.line.minimum=0.83 -Dcoverage.branch.minimum=0.73
fi

if wants 2e; then
    echo "[2e/3] bootstrap-copy with the media piece, without the example (package com.acme.media)"
    media="$WORK/media"
    mkdir -p "$media/src/main/java/com/acme/media"
    cp -r "$REFERENCE/mvnw" "$REFERENCE/.mvn" "$media/"
    sed -e 's|<artifactId>reference-app</artifactId>|<artifactId>media</artifactId>|' -e 's|<name>reference-app</name>|<name>media</name>|' \
        "$REFERENCE/pom.xml" > "$media/pom.xml"
    sed -e 's/package com.acme.smoke;/package com.acme.media;/' -e 's/SmokeApplication/MediaApplication/g' \
        "$project/src/main/java/com/acme/smoke/SmokeApplication.java" > "$media/src/main/java/com/acme/media/MediaApplication.java"
    "$REPO/scripts/bootstrap-copy.sh" --target "$media" --package com.acme.media --with core,sign-in,media > "$WORK/copy-media.log" 2>&1
    grep -rqE "(example|metadata|media|admin):(begin|end)|TODO_ATTACHMENT|TodoAttachment|todo_attachments|TODO_LIST|METADATA_" "$media/src" && { echo "FAILED: samples, todo code or markers left in a media copy without the example piece" >&2; exit 1; }
    [ -f "$media/src/main/resources/db/changelog/changes/0007-users-admin-role.sql" ] && grep -q "ADMIN" "$media/src/main/java/com/acme/media/user/domain/Role.java" \
        || { echo "FAILED: the ADMIN role is missing from the media copy (its policy admin endpoint needs it)" >&2; exit 1; }
    [ -f "$media/src/main/resources/db/changelog/changes/0010-todo-attachments.sql" ] && { echo "FAILED: the todo attachments changeset in a copy without the example" >&2; exit 1; }
    no_session_version "$media/src" "$media/docs"
    no_change_history "$media/src" "$media/docs"
    no_roles "$media/src" "$media/docs" "$media/spotbugs-exclude.xml"
    # The media endpoints' own tests arrive with the piece (skipped until the project declares a purpose).
    for endpoint_test in UploadUrlIntegrationTest UploadPolicyIntegrationTest PresignedUploadIntegrationTest; do
        [ -f "$media/src/test/java/com/acme/media/media/$endpoint_test.java" ] || { echo "FAILED: $endpoint_test is missing from the media copy" >&2; exit 1; }
    done
    # Coverage measured 2026-11-18 at lines 82.59 %, branches 70.62 %; only ever raised.
    mvn_verify "$media" "$WORK/media.log" -Dcoverage.line.minimum=0.81 -Dcoverage.branch.minimum=0.69
fi

if wants 2f; then
    echo "[2f/3] bootstrap-copy with the example and media pieces (package com.acme.files)"
    files="$WORK/files"
    mkdir -p "$files/src/main/java/com/acme/files"
    cp -r "$REFERENCE/mvnw" "$REFERENCE/.mvn" "$files/"
    sed -e 's|<artifactId>reference-app</artifactId>|<artifactId>files</artifactId>|' -e 's|<name>reference-app</name>|<name>files</name>|' \
        "$REFERENCE/pom.xml" > "$files/pom.xml"
    sed -e 's/package com.acme.smoke;/package com.acme.files;/' -e 's/SmokeApplication/FilesApplication/g' \
        "$project/src/main/java/com/acme/smoke/SmokeApplication.java" > "$files/src/main/java/com/acme/files/FilesApplication.java"
    "$REPO/scripts/bootstrap-copy.sh" --target "$files" --package com.acme.files --with core,sign-in,example,media,session-version > "$WORK/copy-files.log" 2>&1
    grep -rqE "(example|metadata|media|admin|session-version):(begin|else|end)|METADATA_|MetadataKey" "$files/src" "$files/docs" \
        && { echo "FAILED: markers or metadata entries left in the example and media copy" >&2; exit 1; }
    # The session version per account: the users column, not the membership's (no tenancy here).
    [ -f "$files/src/main/resources/db/changelog/changes/0021-users-session-version.sql" ] \
        && [ ! -f "$files/src/main/resources/db/changelog/changes/0022-memberships-session-version.sql" ] \
        && grep -q "0021-users-session-version.sql" "$files/src/main/resources/db/changelog/db.changelog-master.yaml" \
        && [ -f "$files/src/main/java/com/acme/files/common/security/SessionVersionFilter.java" ] \
        && grep -q "session_version" "$files/src/main/java/com/acme/files/user/domain/User.java" \
        || { echo "FAILED: the session version per account is incomplete in the copy with the session-version piece" >&2; exit 1; }
    grep -rqE "membership_id|MEMBERSHIP_CLAIM|TenantDeactivated|MembershipService" "$files/src" && { echo "FAILED: the session version's tenancy parts in a copy without tenancy" >&2; exit 1; }
    grep -q "SESSION_VERSION_STALE" "$files/src/main/java/com/acme/files/user/domain/Role.java" \
        || { echo "FAILED: the Role javadoc doesn't say a role change reaches the next request with the session version" >&2; exit 1; }
    no_change_history "$files/src" "$files/docs"
    no_roles "$files/src" "$files/docs" "$files/spotbugs-exclude.xml"
    # The display number comes with the metadata piece (Counters): not in this copy.
    [ -f "$files/src/main/resources/db/changelog/changes/0026-todo-numbers.sql" ] && { echo "FAILED: the todo numbers in a copy without the metadata piece" >&2; exit 1; }
    for attachment in main/java/com/acme/files/todo/domain/TodoAttachment.java test/java/com/acme/files/todo/TodoAttachmentsIntegrationTest.java \
                      main/resources/db/changelog/changes/0010-todo-attachments.sql; do
        [ -f "$files/src/$attachment" ] || { echo "FAILED: $attachment is missing from the example and media copy" >&2; exit 1; }
    done
    # The printed dependency lists name every dependency of the reference pom (bootstrap.md §3 proposes them).
    for artifact in $(sed -n '/<dependencyManagement>/,/<\/dependencyManagement>/d; /<dependencies>/,/<\/dependencies>/p' "$REFERENCE/pom.xml" | grep -oP '(?<=<artifactId>)[^<]+'); do
        grep -qE "(^|[ ,:(])$artifact([ ,;(]|\$)" "$WORK/copy-files.log" \
            || { echo "FAILED: $artifact (reference pom) is missing from bootstrap-copy.sh's dependency lists" >&2; exit 1; }
    done
    # Everything compiles and static analysis reads it all; the tests that need both pieces run (the others ran in 1,
    # 2b and 2e), so coverage, a measure of the whole suite, isn't checked here.
    mvn_verify "$files" "$WORK/files.log" -Djacoco.skip=true \
        -Dtest='TodoAttachmentsIntegrationTest,Upload*IntegrationTest,PresignedUploadIntegrationTest,UploadPurposeTest,*ArchTest,ErrorCodeContractTest,LimitsIntegrationTest,SessionVersion*Test,AuthIntegrationTest' \
        -Dsurefire.failIfNoSpecifiedTests=false
fi

if wants 2g; then
    echo "[2g/3] bootstrap-copy with schema per tenant, without the example (package com.acme.tenancy)"
    tenancy="$WORK/tenancy"
    mkdir -p "$tenancy/src/main/java/com/acme/tenancy"
    cp -r "$REFERENCE/mvnw" "$REFERENCE/.mvn" "$tenancy/"
    sed -e 's|<artifactId>reference-app</artifactId>|<artifactId>tenancy</artifactId>|' -e 's|<name>reference-app</name>|<name>tenancy</name>|' \
        "$REFERENCE/pom.xml" > "$tenancy/pom.xml"
    sed -e 's/package com.acme.smoke;/package com.acme.tenancy;/' -e 's/SmokeApplication/TenancyApplication/g' \
        "$project/src/main/java/com/acme/smoke/SmokeApplication.java" > "$tenancy/src/main/java/com/acme/tenancy/TenancyApplication.java"
    "$REPO/scripts/bootstrap-copy.sh" --target "$tenancy" --package com.acme.tenancy --with core,sign-in,tenancy-schema > "$WORK/copy-tenancy.log" 2>&1
    grep -rqE "(example|metadata|media|admin|tenancy|tenancy-schema|tenancy-row|tenant-registration):(begin|else|end)|TODO_LIST|todo_lists|METADATA_|media_files|UploadPurpose|TenantOwnedEntity|app\.tenant_id" "$tenancy/src" \
        && { echo "FAILED: markers or other pieces' entries left in the tenancy copy" >&2; exit 1; }
    [ -f "$tenancy/src/main/java/com/acme/tenancy/config/LazyConnectionConfig.java" ] && { echo "FAILED: the lazy proxy in a tenancy copy (the hook borrows lazily)" >&2; exit 1; }
    # The token's role is the membership's: no operator statement on users.role in the Role javadoc.
    grep -q "UPDATE users" "$tenancy/src/main/java/com/acme/tenancy/user/domain/Role.java" \
        && { echo "FAILED: the Role javadoc of a tenancy copy names the users.role statement (the role is the membership's)" >&2; exit 1; }
    no_session_version "$tenancy/src" "$tenancy/docs"
    no_change_history "$tenancy/src" "$tenancy/docs"
    no_roles "$tenancy/src" "$tenancy/docs" "$tenancy/spotbugs-exclude.xml"
    # Accounts are global: platform staff suspend them, never a tenant ADMIN through the tenant plane.
    grep -q '"/{id}/status"' "$tenancy/src/main/java/com/acme/tenancy/user/controller/UserController.java" \
        && { echo "FAILED: the tenant-plane account suspension endpoint in a tenancy copy" >&2; exit 1; }
    [ -f "$tenancy/src/main/resources/db/changelog/db.changelog-master.yaml" ] && { echo "FAILED: a single master in a schema-per-tenant copy" >&2; exit 1; }
    [ -f "$tenancy/src/main/java/com/acme/tenancy/user/controller/TenantRegistrationController.java" ] \
        && { echo "FAILED: the public registration endpoint in a copy without tenant-registration" >&2; exit 1; }
    grep -q "0012-tenancy.sql" "$tenancy/src/main/resources/db/changelog/platform.yaml" \
        && [ "$(grep -c "file:" "$tenancy/src/main/resources/db/changelog/tenant.yaml")" = 1 ] \
        && grep -q "changes/0017-members.sql" "$tenancy/src/main/resources/db/changelog/tenant.yaml" \
        || { echo "FAILED: the tenancy copy's masters don't list its changesets as platform and tenant ones" >&2; exit 1; }
    # Coverage measured 2026-11-18 at lines 93.68 %, branches 79.42 %; only ever raised.
    mvn_verify "$tenancy" "$WORK/tenancy.log" -Dcoverage.line.minimum=0.92 -Dcoverage.branch.minimum=0.78
fi

if wants 2h; then
    echo "[2h/3] bootstrap-copy with every piece, schema per tenant and self-registration included (package com.acme.tenants)"
    tenants="$WORK/tenants"
    mkdir -p "$tenants/src/main/java/com/acme/tenants"
    cp -r "$REFERENCE/mvnw" "$REFERENCE/.mvn" "$tenants/"
    sed -e 's|<artifactId>reference-app</artifactId>|<artifactId>tenants</artifactId>|' -e 's|<name>reference-app</name>|<name>tenants</name>|' \
        "$REFERENCE/pom.xml" > "$tenants/pom.xml"
    sed -e 's/package com.acme.smoke;/package com.acme.tenants;/' -e 's/SmokeApplication/TenantsApplication/g' \
        "$project/src/main/java/com/acme/smoke/SmokeApplication.java" > "$tenants/src/main/java/com/acme/tenants/TenantsApplication.java"
    "$REPO/scripts/bootstrap-copy.sh" --target "$tenants" --package com.acme.tenants \
        --with core,sign-in,example,metadata,media,tenancy-schema,tenant-registration,session-version,change-history > "$WORK/copy-tenants.log" 2>&1
    grep -rqE "(sign-in|example|metadata|media|admin|tenancy|tenancy-schema|tenancy-row|tenant-registration|session-version|change-history):(begin|else|end)" "$tenants/src" "$tenants/compose.yaml" "$tenants/.env.example" "$tenants/docs" \
        && { echo "FAILED: markers left in the copy with every piece" >&2; exit 1; }
    grep -rqE "TenantOwnedEntity|app\.tenant_id|ROW LEVEL SECURITY" "$tenants/src" && { echo "FAILED: row mode in a schema-per-tenant copy" >&2; exit 1; }
    no_roles "$tenants/src" "$tenants/docs" "$tenants/spotbugs-exclude.xml"
    grep -q "0002-todo-lists.sql" "$tenants/src/main/resources/db/changelog/tenant.yaml" && grep -q "0009-media-files.sql" "$tenants/src/main/resources/db/changelog/tenant.yaml" \
        && ! grep -q "0002-todo-lists.sql" "$tenants/src/main/resources/db/changelog/platform.yaml" \
        || { echo "FAILED: the todo and media tables are not in the tenant changelog alone" >&2; exit 1; }
    grep -q "docker-entrypoint-initdb.d" "$tenants/compose.yaml" && [ -x "$tenants/docker/postgres-init/10-tenancy-roles.sh" ] \
        || { echo "FAILED: compose.yaml doesn't create the tenancy roles in the copy" >&2; exit 1; }
    # The session version per membership: a platform changeset; the users column stays out.
    grep -q "0022-memberships-session-version.sql" "$tenants/src/main/resources/db/changelog/platform.yaml" \
        && [ ! -f "$tenants/src/main/resources/db/changelog/changes/0021-users-session-version.sql" ] \
        && ! grep -q "session_version" "$tenants/src/main/java/com/acme/tenants/user/domain/User.java" \
        || { echo "FAILED: the session version isn't per membership in the schema-per-tenant copy" >&2; exit 1; }
    grep -q "0026-todo-numbers.sql" "$tenants/src/main/resources/db/changelog/tenant.yaml" \
        && [ -f "$tenants/src/test/resources/db/upgrade/previous-release-tenant-seed.sql" ] \
        || { echo "FAILED: the todo numbers or the tenant schemas' previous-release seed are missing from the schema-per-tenant copy" >&2; exit 1; }
    [ -f "$tenants/src/main/java/com/acme/tenants/user/api/Users.java" ] \
        && { echo "FAILED: the accounts' facade in a tenancy copy (a person in a tenant is a member: Members)" >&2; exit 1; }
    # The change history: one platform table and function, the example's triggers in every tenant schema.
    grep -q "0035-change-history.sql" "$tenants/src/main/resources/db/changelog/platform.yaml" \
        && grep -q "0036-todos-change-history.sql" "$tenants/src/main/resources/db/changelog/tenant.yaml" \
        && ! grep -q "0036-todos-change-history.sql" "$tenants/src/main/resources/db/changelog/platform.yaml" \
        || { echo "FAILED: the change history isn't the platform's, with the example's triggers in the tenant changelog" >&2; exit 1; }
    # Coverage measured 2026-11-18 at lines 95.99 %, branches 84.78 %; only ever raised.
    mvn_verify "$tenants" "$WORK/tenants.log" "$UPGRADE" -Dcoverage.line.minimum=0.94 -Dcoverage.branch.minimum=0.83
    upgraded "$tenants"
fi

if wants 2i; then
    echo "[2i/3] bootstrap-copy with row mode, without the example (package com.acme.rows)"
    rows="$WORK/rows"
    mkdir -p "$rows/src/main/java/com/acme/rows"
    cp -r "$REFERENCE/mvnw" "$REFERENCE/.mvn" "$rows/"
    sed -e 's|<artifactId>reference-app</artifactId>|<artifactId>rows</artifactId>|' -e 's|<name>reference-app</name>|<name>rows</name>|' \
        "$REFERENCE/pom.xml" > "$rows/pom.xml"
    sed -e 's/package com.acme.smoke;/package com.acme.rows;/' -e 's/SmokeApplication/RowsApplication/g' \
        "$project/src/main/java/com/acme/smoke/SmokeApplication.java" > "$rows/src/main/java/com/acme/rows/RowsApplication.java"
    "$REPO/scripts/bootstrap-copy.sh" --target "$rows" --package com.acme.rows --with core,sign-in,tenancy-row > "$WORK/copy-rows.log" 2>&1
    grep -rqE "(example|metadata|media|admin|tenancy|tenancy-schema|tenancy-row|tenant-registration):(begin|else|end)|TODO_LIST|todo_lists|METADATA_|media_files|UploadPurpose|UploadLimits" "$rows/src" \
        && { echo "FAILED: markers or other pieces' entries left in the row-mode copy" >&2; exit 1; }
    grep -rqE "TenantSchemas|search_path|tenant_template|TenantMigration" "$rows/src" && { echo "FAILED: schema-per-tenant code in a row-mode copy" >&2; exit 1; }
    [ -f "$rows/src/main/java/com/acme/rows/config/LazyConnectionConfig.java" ] && { echo "FAILED: the lazy proxy in a row-mode copy (the hook borrows lazily)" >&2; exit 1; }
    no_session_version "$rows/src" "$rows/docs"
    no_change_history "$rows/src" "$rows/docs"
    no_roles "$rows/src" "$rows/docs" "$rows/spotbugs-exclude.xml"
    [ -f "$rows/src/main/resources/db/changelog/platform.yaml" ] && { echo "FAILED: two masters in a row-mode copy" >&2; exit 1; }
    [ -f "$rows/src/main/java/com/acme/rows/user/controller/TenantRegistrationController.java" ] \
        && { echo "FAILED: the public registration endpoint in a copy without tenant-registration" >&2; exit 1; }
    grep -q "0012-tenancy.sql" "$rows/src/main/resources/db/changelog/db.changelog-master.yaml" \
        && grep -q "0013-row-security.sql" "$rows/src/main/resources/db/changelog/db.changelog-master.yaml" \
        || { echo "FAILED: the row-mode master doesn't list the tenancy changesets" >&2; exit 1; }
    # Coverage measured 2026-11-18 at lines 93.26 %, branches 79.45 %; only ever raised.
    mvn_verify "$rows" "$WORK/rows.log" -Dcoverage.line.minimum=0.92 -Dcoverage.branch.minimum=0.78
fi

if wants 2j; then
    echo "[2j/3] bootstrap-copy with every piece, row mode and self-registration included (package com.acme.rowtenants)"
    rowtenants="$WORK/rowtenants"
    mkdir -p "$rowtenants/src/main/java/com/acme/rowtenants"
    cp -r "$REFERENCE/mvnw" "$REFERENCE/.mvn" "$rowtenants/"
    sed -e 's|<artifactId>reference-app</artifactId>|<artifactId>rowtenants</artifactId>|' -e 's|<name>reference-app</name>|<name>rowtenants</name>|' \
        "$REFERENCE/pom.xml" > "$rowtenants/pom.xml"
    sed -e 's/package com.acme.smoke;/package com.acme.rowtenants;/' -e 's/SmokeApplication/RowtenantsApplication/g' \
        "$project/src/main/java/com/acme/smoke/SmokeApplication.java" > "$rowtenants/src/main/java/com/acme/rowtenants/RowtenantsApplication.java"
    "$REPO/scripts/bootstrap-copy.sh" --target "$rowtenants" --package com.acme.rowtenants \
        --with core,sign-in,example,metadata,media,tenancy-row,tenant-registration,session-version,change-history > "$WORK/copy-rowtenants.log" 2>&1
    grep -rqE "(sign-in|example|metadata|media|admin|tenancy|tenancy-schema|tenancy-row|tenant-registration|session-version|change-history):(begin|else|end)" "$rowtenants/src" "$rowtenants/compose.yaml" "$rowtenants/.env.example" "$rowtenants/docs" \
        && { echo "FAILED: markers left in the row-mode copy with every piece" >&2; exit 1; }
    no_roles "$rowtenants/src" "$rowtenants/docs" "$rowtenants/spotbugs-exclude.xml"
    rowmaster="$rowtenants/src/main/resources/db/changelog/db.changelog-master.yaml"
    # The platform's changesets first: every tenant table references tenants.
    [ "$(grep -n "0012-tenancy.sql" "$rowmaster" | cut -d: -f1)" -lt "$(grep -n "0002-todo-lists.sql" "$rowmaster" | cut -d: -f1)" ] \
        || { echo "FAILED: the row-mode master lists a tenant table before the tenants table" >&2; exit 1; }
    [ "$(cat "$rowtenants"/src/main/resources/db/changelog/changes/*.sql | grep -c "ENABLE ROW LEVEL SECURITY")" -ge 11 ] \
        || { echo "FAILED: the reference's tenant tables lost their policies in the row-mode copy" >&2; exit 1; }
    [ -f "$rowtenants/src/main/java/com/acme/rowtenants/todo/service/TodoAttachmentPolicySeed.java" ] \
        || { echo "FAILED: the todo attachments' seed listener is missing from the row-mode copy" >&2; exit 1; }
    grep -q "0023-owner-tenant-statistics.sql" "$rowmaster" \
        || { echo "FAILED: the owners' dependency statistics are missing from the row-mode master" >&2; exit 1; }
    [ "$(grep -n "0022-memberships-session-version.sql" "$rowmaster" | cut -d: -f1)" -lt "$(grep -n "0002-todo-lists.sql" "$rowmaster" | cut -d: -f1)" ] \
        && [ ! -f "$rowtenants/src/main/resources/db/changelog/changes/0021-users-session-version.sql" ] \
        || { echo "FAILED: the session version isn't per membership, with the platform's changesets, in the row-mode copy" >&2; exit 1; }
    grep -q "docker-entrypoint-initdb.d" "$rowtenants/compose.yaml" && [ -x "$rowtenants/docker/postgres-init/10-tenancy-roles.sh" ] \
        && ! grep -q "search_path" "$rowtenants/docker/postgres-init/10-tenancy-roles.sh" \
        || { echo "FAILED: compose.yaml doesn't create the row-mode roles in the copy" >&2; exit 1; }
    grep -q "0026-todo-numbers.sql" "$rowmaster" && [ -f "$rowtenants/src/main/java/com/acme/rowtenants/todo/service/TodoNumberSeed.java" ] \
        || { echo "FAILED: the todo numbers' changeset or their seed listener is missing from the row-mode copy" >&2; exit 1; }
    [ -f "$rowtenants/src/main/java/com/acme/rowtenants/user/api/Users.java" ] \
        && { echo "FAILED: the accounts' facade in a tenancy copy (a person in a tenant is a member: Members)" >&2; exit 1; }
    # Coverage measured 2026-11-18 at lines 95.96 %, branches 84.98 %; only ever raised.
    mvn_verify "$rowtenants" "$WORK/rowtenants.log" "$UPGRADE" -Dcoverage.line.minimum=0.94 -Dcoverage.branch.minimum=0.83
    upgraded "$rowtenants"
fi

# Every piece with the roles piece (Level 2): $1 stage id, $2 directory and application name, $3 tenancy pieces ("" for
# none), $4 and $5 the copy's coverage minimums (lines, branches).
every_piece_with_roles() {
    local id="$1" name="$2" tenancy="$3" line_minimum="$4" branch_minimum="$5" dir pieces
    dir="$WORK/$name"
    pieces="core,sign-in,example,metadata,media,session-version,change-history,roles${tenancy:+,$tenancy}"
    mkdir -p "$dir/src/main/java/com/acme/$name"
    cp -r "$REFERENCE/mvnw" "$REFERENCE/.mvn" "$dir/"
    sed -e "s|<artifactId>reference-app</artifactId>|<artifactId>$name</artifactId>|" -e "s|<name>reference-app</name>|<name>$name</name>|" \
        "$REFERENCE/pom.xml" > "$dir/pom.xml"
    sed -e "s/package com.acme.smoke;/package com.acme.$name;/" -e "s/SmokeApplication/${name^}Application/g" \
        "$project/src/main/java/com/acme/smoke/SmokeApplication.java" > "$dir/src/main/java/com/acme/$name/${name^}Application.java"
    "$REPO/scripts/bootstrap-copy.sh" --target "$dir" --package "com.acme.$name" --with "$pieces" > "$WORK/copy-$name.log" 2>&1
    grep -rqE "(sign-in|example|metadata|media|admin|tenancy|tenancy-schema|tenancy-row|tenant-registration|session-version|change-history|roles):(begin|else|end)" "$dir/src" "$dir/compose.yaml" "$dir/.env.example" "$dir/docs" \
        && { echo "FAILED: markers left in the $id copy" >&2; exit 1; }
    # One source of a person's access: no role column on the account or the membership, no roles claim, the
    # TokenPermissions that reads the tables.
    grep -rqE "private Role role;|ROLES_CLAIM" "$dir/src/main/java" \
        && { echo "FAILED: a role column or the token's roles claim left in the $id copy (the tables are the one source)" >&2; exit 1; }
    grep -q "PersonAccessService" "$dir/src/main/java/com/acme/$name/user/service/RolePermissions.java" \
        && ls "$dir/src/main/resources/db/changelog/changes/"*-roles.sql > /dev/null \
        || { echo "FAILED: the roles piece's TokenPermissions or changeset is missing from the $id copy" >&2; exit 1; }
    local changes="$dir/src/main/resources/db/changelog/changes" user="$dir/src/main/java/com/acme/$name/user"
    if [ -n "$tenancy" ]; then
        # A member's role change is the assignment endpoints; adding and removing a member are the Manager's.
        [ -f "$user/service/TenantMemberManager.java" ] && [ ! -f "$user/dto/request/TenantMemberUpdateRequest.java" ] \
            && [ ! -f "$dir/src/test/java/com/acme/$name/user/TenantMemberIntegrationTest.java" ] \
            && [ -f "$dir/src/test/java/com/acme/$name/user/TenantAccessIntegrationTest.java" ] \
            && [ ! -f "$user/dto/internal/AccountCreated.java" ] \
            || { echo "FAILED: the members' role change or the tenant access Manager isn't the roles piece's in the $id copy" >&2; exit 1; }
    else
        [ -f "$user/dto/internal/AccountCreated.java" ] && [ ! -f "$user/service/TenantMemberManager.java" ] \
            || { echo "FAILED: signup's first role or the tenant Manager is wrong in the $id copy without tenancy" >&2; exit 1; }
    fi
    if [[ "$tenancy" == *tenancy-schema* ]]; then
        # The roles in every tenant schema; the platform's role column expanded, then contracted once every schema copied.
        local platform="$dir/src/main/resources/db/changelog/platform.yaml" tenant="$dir/src/main/resources/db/changelog/tenant.yaml"
        grep -q "0037-roles.sql" "$tenant" && ! grep -q "0037-roles.sql" "$platform" \
            && [ "$(grep -n "0038-memberships-role-expand.sql" "$platform" | cut -d: -f1)" -lt "$(grep -n "0039-memberships-role-contract.sql" "$platform" | cut -d: -f1)" ] \
            || { echo "FAILED: the roles changesets aren't the tenant's (0037) and the platform's (0038, 0039) in the $id copy" >&2; exit 1; }
    else
        [ ! -f "$changes/0038-memberships-role-expand.sql" ] && [ ! -f "$changes/0039-memberships-role-contract.sql" ] \
            || { echo "FAILED: schema per tenant's role-column changesets in the $id copy" >&2; exit 1; }
    fi
    mvn_verify "$dir" "$WORK/$name.log" "$UPGRADE" -Dcoverage.line.minimum="$line_minimum" -Dcoverage.branch.minimum="$branch_minimum"
    upgraded "$dir"
}

if wants 2n; then
    echo "[2n/3] bootstrap-copy with every piece and the roles piece, without tenancy (package com.acme.roles)"
    # Coverage measured 2026-11-19 at lines 95.33 %, branches 84.92 %; only ever raised.
    every_piece_with_roles 2n roles "" 0.94 0.83
fi

if wants 2o; then
    echo "[2o/3] bootstrap-copy with every piece and the roles piece, row mode (package com.acme.rowroles)"
    # Coverage measured 2026-11-19 at lines 0.00 %, branches 0.00 %; only ever raised.
    every_piece_with_roles 2o rowroles "tenancy-row,tenant-registration" 0.50 0.50
fi

if wants 2p; then
    echo "[2p/3] bootstrap-copy with every piece and the roles piece, schema per tenant (package com.acme.schemaroles)"
    # Coverage measured 2026-11-19 at lines 0.00 %, branches 0.00 %; only ever raised.
    every_piece_with_roles 2p schemaroles "tenancy-schema,tenant-registration" 0.50 0.50
fi

# Every piece but the example, per tenancy mode: what a project starts from before its first entity.
# $1 stage id, $2 directory and application name, $3 tenancy pieces ("" for none).
# $4, $5: the copy's coverage minimums (lines, branches).
every_piece_but_the_example() {
    local id="$1" name="$2" tenancy="$3" line_minimum="$4" branch_minimum="$5" dir pieces
    dir="$WORK/$name"
    pieces="core,sign-in,metadata,media,session-version,change-history${tenancy:+,$tenancy}"
    mkdir -p "$dir/src/main/java/com/acme/$name"
    cp -r "$REFERENCE/mvnw" "$REFERENCE/.mvn" "$dir/"
    sed -e "s|<artifactId>reference-app</artifactId>|<artifactId>$name</artifactId>|" -e "s|<name>reference-app</name>|<name>$name</name>|" \
        "$REFERENCE/pom.xml" > "$dir/pom.xml"
    sed -e "s/package com.acme.smoke;/package com.acme.$name;/" -e "s/SmokeApplication/${name^}Application/g" \
        "$project/src/main/java/com/acme/smoke/SmokeApplication.java" > "$dir/src/main/java/com/acme/$name/${name^}Application.java"
    "$REPO/scripts/bootstrap-copy.sh" --target "$dir" --package "com.acme.$name" --with "$pieces" > "$WORK/copy-$name.log" 2>&1
    grep -rqE "(sign-in|example|metadata|media|admin|tenancy|tenancy-schema|tenancy-row|tenant-registration|session-version|change-history):(begin|else|end)" "$dir/src" "$dir/compose.yaml" "$dir/.env.example" "$dir/docs" \
        && { echo "FAILED: markers left in the $id copy" >&2; exit 1; }
    grep -rqE "TODO_LIST|TODO_ATTACHMENT|todo_lists|TodoListSettings|com\.acme\.$name\.(todo|planning)\b" "$dir/src" \
        && { echo "FAILED: the example's entries left in the $id copy, which has no example" >&2; exit 1; }
    no_roles "$dir/src" "$dir/docs" "$dir/spotbugs-exclude.xml"
    mvn_verify "$dir" "$WORK/$name.log" -Dcoverage.line.minimum="$line_minimum" -Dcoverage.branch.minimum="$branch_minimum"
}

if wants 2k; then
    echo "[2k/3] bootstrap-copy with every piece but the example, without tenancy (package com.acme.pieces)"
    # Coverage measured 2026-11-18 at lines 79.43 %, branches 69.09 %; only ever raised.
    every_piece_but_the_example 2k pieces "" 0.78 0.68
fi

if wants 2l; then
    echo "[2l/3] bootstrap-copy with every piece but the example, schema per tenant (package com.acme.schemapieces)"
    # Coverage measured 2026-11-18 at lines 86.26 %, branches 73.33 %; only ever raised.
    every_piece_but_the_example 2l schemapieces "tenancy-schema,tenant-registration" 0.85 0.72
fi

if wants 2m; then
    echo "[2m/3] bootstrap-copy with every piece but the example, row mode (package com.acme.rowpieces)"
    # Coverage measured 2026-11-18 at lines 85.09 %, branches 72.84 %; only ever raised.
    every_piece_but_the_example 2m rowpieces "tenancy-row,tenant-registration" 0.84 0.71
fi

if wants 2d; then
    echo "[2d/3] bootstrap-copy of core alone, without the Redis dependencies (package com.acme.core)"
    core="$WORK/core"
    mkdir -p "$core/src/main/java/com/acme/core"
    cp -r "$REFERENCE/mvnw" "$REFERENCE/.mvn" "$core/"
    # Drops sign-in's and media's dependencies, so code of theirs left in core fails to compile.
    sed -e 's|<artifactId>reference-app</artifactId>|<artifactId>core</artifactId>|' -e 's|<name>reference-app</name>|<name>core</name>|' \
        "$REFERENCE/pom.xml" \
        | perl -0pe 's{\s*<dependency>\s*<groupId>[^<]+</groupId>\s*<artifactId>(?:spring-boot-starter-data-redis|bucket4j_jdk17-core|bucket4j_jdk17-lettuce|s3)</artifactId>.*?</dependency>}{}gs' \
        > "$core/pom.xml"
    grep -qE "<artifactId>(spring-boot-starter-data-redis|bucket4j_jdk17-[a-z]+|s3)</artifactId>" "$core/pom.xml" && { echo "FAILED: could not drop the Redis and S3 dependencies from the core pom" >&2; exit 1; }
    sed -e 's/package com.acme.smoke;/package com.acme.core;/' -e 's/SmokeApplication/CoreApplication/g' \
        "$project/src/main/java/com/acme/smoke/SmokeApplication.java" > "$core/src/main/java/com/acme/core/CoreApplication.java"
    "$REPO/scripts/bootstrap-copy.sh" --target "$core" --package com.acme.core --with core > "$WORK/copy-core.log"
    grep -rqE "sign-in:(begin|end)|media:(begin|end)|RedisRateLimiter|RedisClientConfig|REDIS_|MEDIA_(BUCKET|STORAGE)|GenericContainer|image: redis" "$core/src" "$core/compose.yaml" "$core/.env.example" \
        && { echo "FAILED: Redis rate limiting, media or their markers left in a core copy" >&2; exit 1; }
    [ -d "$core/src/main/java/com/acme/core/user" ] && { echo "FAILED: the user module in a core copy" >&2; exit 1; }
    no_session_version "$core/src" "$core/docs"
    no_change_history "$core/src" "$core/docs"
    no_roles "$core/src" "$core/docs" "$core/spotbugs-exclude.xml"
    # The unit tests alone can't reach the suite's coverage minimum: static analysis runs, coverage doesn't.
    mvn_verify "$core" "$WORK/core.log" -Dtest='!*IntegrationTest,!*ArchTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
fi

if wants image; then
    echo "[3/3] reference-app image: prod profile against PostgreSQL and Redis"
    docker build -q -t "$RUN-image" "$REFERENCE" > /dev/null
    docker network create "$RUN-net" > /dev/null
    docker run -d --name "$RUN-pg" --network "$RUN-net" -e POSTGRES_DB=reference -e POSTGRES_USER=verify \
        -e POSTGRES_PASSWORD=verify-password postgres:18 > /dev/null
    docker run -d --name "$RUN-redis" --network "$RUN-net" redis:8 > /dev/null
    docker run -d --name "$RUN-app" --network "$RUN-net" -m 768m \
        -e SPRING_PROFILES_ACTIVE=prod -e DB_HOST="$RUN-pg" -e DB_NAME=reference -e DB_USER=verify -e DB_PASSWORD=verify-password \
        -e REDIS_HOST="$RUN-redis" -e JWT_SECRET="$(head -c 48 /dev/urandom | base64)" \
        -e MEDIA_BUCKET=verify-media -e MEDIA_REGION=eu-central-1 \
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
    # Read whole before the grep: grep -q stops at its first match, and under pipefail the curl it cut off would
    # fail the check at random.
    metrics="$(in_app 'curl -sf localhost:8081/actuator/prometheus')"
    grep -q '^rate_limit' <<< "$metrics" || { echo "FAILED: no rate-limit metrics on the management port" >&2; exit 1; }
    # A path the security firewall refuses gets the house Problem Details with its request id, on the real
    # server (MockMvc never sends a rejected request to Boot's /error). Tomcat refuses an encoded '/', '\' or
    # NUL before any filter, with its own 400 page: no setting changes that, so only the status is checked.
    for path in '/api/v1/users//status' '/api/v1/users/me;x=1' '/api/v1/users/%2e%2e/me'; do
        answer="$(in_app "curl -s --path-as-is -D - 'localhost:8080$path'")"
        grep -q '"errorCode":"REQUEST_INVALID"' <<< "$answer" && grep -qi '^x-request-id:' <<< "$answer" \
            || { echo "FAILED: $path isn't Problem Details REQUEST_INVALID with a request id: $answer" >&2; exit 1; }
    done
    for path in '/api/v1/users/a%2Fb' '/api/v1/users/a%5Cb' '/api/v1/users/a%00b'; do
        [ "$(in_app "curl -s --path-as-is -o /dev/null -w '%{http_code}' 'localhost:8080$path'")" = 400 ] \
            || { echo "FAILED: $path isn't refused 400 by Tomcat" >&2; exit 1; }
    done
    echo "container: healthy, rate limited through Redis ($codes), metrics on the management port only, refused paths 400"
fi

if [ -n "$only" ]; then
    echo "verified: stages $only are green"
    echo "partial run: fingerprint not recorded"
    exit 0
fi
if [ "$(working_fingerprint "$REPO")" = "$fingerprint" ]; then
    echo "$fingerprint" > "$(verified_record "$REPO")"
    echo "recorded: a push of exactly this code passes the pre-push check without running this again"
else
    echo "files changed during the run: nothing recorded, run it again before pushing" >&2
fi
echo "verified: reference-app, the bootstrap copies and the image are green"
