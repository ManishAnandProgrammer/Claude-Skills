#!/usr/bin/env bash
# Copies pieces of the house reference application (reference-app/) into a Spring Boot project,
# renaming its root package. Used by /crud-gen bootstrap; safe on an existing project:
# - a file that already exists in the target is never overwritten: it is skipped and reported;
# - nothing outside src/, the project's root config files and docs/ is touched (pom.xml isn't:
#   the dependencies each piece needs are printed, to be added after the owner approves them).
#
# Usage: bootstrap-copy.sh --target <project dir> --package <root package> [--with <pieces>] [--dry-run]
#   pieces (comma-separated, default core,sign-in):
#     core     error contract, wrappers, paging, limits, logging, admission, security config,
#              persistence base, config files, test base, ArchUnit, the incident runbook (docs/)
#     sign-in  user module (signup, login, tokens) and its Redis rate limiting: the limiter in
#              common/ratelimit, config/RedisClientConfig, the Redis settings and Testcontainer
#     example  the golden crud-gen modules (todo lists and todos; plans, in a module of their own), for
#              reference only
#     metadata configuration metadata (a typed record per key, admin GET/PUT of fixed keys), gapless
#              counters
#     media    uploads straight to S3 with presigned PUTs: upload policies (admin PUT), upload URLs,
#              the claim/release API for owning records, the verification and cleanup jobs; with the
#              example, the todo's attachments
#   metadata and media have admin endpoints: with sign-in, either brings the ADMIN role and the test
#   base's newAdmin() (the "admin" markers).
# Shared files (ErrorCode, messages, limits, db.yml, compose.yaml, .env.example, Role, test base,
# ArchUnit) mark a piece's entries with "<piece>:begin" / "<piece>:end" comment lines (sign-in,
# example, metadata, media, admin): without that piece those blocks are left out; blocks may nest
# (a media block inside an example block arrives only with both). A file a piece names on its own
# belongs to that piece, even inside a directory another piece copies (sign-in's rate limiter in
# core's common/ and config/, media's todo attachments in the example's todo/). Sample content of an
# optional piece arrives only with the example too: the metadata piece's sample keys, the media
# piece's purpose TODO_ATTACHMENT, and their tests (the media piece's own endpoint tests run against
# whichever purpose comes first, and are skipped until the project declares one).
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
REFERENCE="$REPO/reference-app"
REFERENCE_PACKAGE="com.example.app"

die() { echo "error: $*" >&2; exit 1; }

target="" package="" pieces="core,sign-in" dry_run=false
while [ $# -gt 0 ]; do
    case "$1" in
        --target) target="${2:-}"; shift 2 ;;
        --package) package="${2:-}"; shift 2 ;;
        --with) pieces="${2:-}"; shift 2 ;;
        --dry-run) dry_run=true; shift ;;
        -h|--help) sed -n '2,/^[^#]/{/^#/p}' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "unknown option: $1 (see --help)" ;;
    esac
done
[ -n "$target" ] || die "--target is required"
[ -n "$package" ] || die "--package is required"
[[ "$package" =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]] || die "--package must be a lower-case Java package such as com.acme.orders"
[ -d "$REFERENCE/src" ] || die "no reference application at $REFERENCE"
target="$(cd "$target" 2>/dev/null && pwd)" || die "target $target doesn't exist"
[ -f "$target/pom.xml" ] || [ -f "$target/build.gradle" ] || [ -f "$target/build.gradle.kts" ] || die "$target has no build file"

reference_path="${REFERENCE_PACKAGE//.//}"
package_path="${package//.//}"

chosen_piece() { [[ ",$pieces," == *",$1,"* ]]; }

# The ADMIN role (Role.ADMIN, its changeset, newAdmin()) comes with the first piece that has admin endpoints.
admin_chosen() { chosen_piece metadata || chosen_piece media; }

# Files per piece, relative to reference-app/. Directories are copied recursively.
files_for() {
    case "$1" in
        core) cat <<LIST
src/main/java/$reference_path/common
src/main/java/$reference_path/config
src/main/resources/application.yml
src/main/resources/config
src/main/resources/logback-spring.xml
src/main/resources/messages
src/test/java/$reference_path/TestcontainersConfiguration.java
src/test/java/$reference_path/support
src/test/java/$reference_path/archunit
src/test/java/$reference_path/common
src/test/java/$reference_path/config
src/test/resources/application-test.yml
src/test/resources/messages-test
compose.yaml
.env.example
Dockerfile
.dockerignore
docs/runbook.md
LIST
            ;;
        sign-in) cat <<LIST
src/main/java/$reference_path/user
src/main/java/$reference_path/common/ratelimit/RateLimit.java
src/main/java/$reference_path/common/ratelimit/RateLimitProperties.java
src/main/java/$reference_path/common/ratelimit/RedisRateLimiter.java
src/main/java/$reference_path/config/RedisClientConfig.java
src/main/resources/db/changelog/changes/0001-users-and-refresh-tokens.sql
src/test/java/$reference_path/user
src/test/java/$reference_path/common/ratelimit/RedisRateLimiterTest.java
src/test/java/$reference_path/config/RedisClientIntegrationTest.java
LIST
            ;;
        example) cat <<LIST
src/main/java/$reference_path/todo
src/main/resources/db/changelog/changes/0002-todo-lists.sql
src/main/resources/db/changelog/changes/0003-todos.sql
src/main/resources/db/changelog/changes/0004-tags.sql
src/main/resources/db/changelog/changes/0005-time-entries.sql
src/main/resources/db/changelog/changes/0006-todo-tags.sql
src/main/java/$reference_path/planning
src/main/resources/db/changelog/changes/0011-plans.sql
src/test/java/$reference_path/todo
src/test/java/$reference_path/planning
LIST
            # The metadata piece's sample configuration and the tests that exercise the mechanism through it.
            if chosen_piece metadata; then cat <<LIST
src/main/java/$reference_path/metadata/api/TodoListSettings.java
src/test/java/$reference_path/metadata/MetadataIntegrationTest.java
LIST
            fi
            ;;
        metadata) cat <<LIST
src/main/java/$reference_path/metadata/package-info.java
src/main/java/$reference_path/metadata/MetadataConstraintErrorCodes.java
src/main/java/$reference_path/metadata/api/Counters.java
src/main/java/$reference_path/metadata/api/Metadata.java
src/main/java/$reference_path/metadata/api/MetadataKey.java
src/main/java/$reference_path/metadata/api/MetadataKeys.java
src/main/java/$reference_path/metadata/controller
src/main/java/$reference_path/metadata/domain
src/main/java/$reference_path/metadata/dto
src/main/java/$reference_path/metadata/mapper
src/main/java/$reference_path/metadata/repository
src/main/java/$reference_path/metadata/service
src/main/resources/db/changelog/changes/0008-configuration-metadata.sql
src/test/java/$reference_path/metadata/CounterIntegrationTest.java
LIST
            # The admin API's role, in the users table that sign-in creates.
            if chosen_piece sign-in; then
                echo "src/main/resources/db/changelog/changes/0007-users-admin-role.sql"
            fi
            ;;
        media) cat <<LIST
src/main/java/$reference_path/media
src/main/resources/config/media.yml
src/main/resources/db/changelog/changes/0009-media-files.sql
src/test/java/$reference_path/support/MediaStorageContainer.java
src/test/java/$reference_path/media/api/FileCountRangeTest.java
src/test/java/$reference_path/media/service/AllowedContentTypesTest.java
src/test/java/$reference_path/media/service/MediaStorageTest.java
src/test/java/$reference_path/media/service/MediaJobsIntegrationTest.java
src/test/java/$reference_path/media/MediaFileIndexIntegrationTest.java
src/test/java/$reference_path/media/UploadUrlIntegrationTest.java
src/test/java/$reference_path/media/UploadPolicyIntegrationTest.java
src/test/java/$reference_path/media/PresignedUploadIntegrationTest.java
src/test/java/$reference_path/media/api/UploadPurposeTest.java
LIST
            # The todo's attachments and the purpose's seed row. The upload endpoint tests above run
            # against the first purpose, whichever it is, and are skipped while there is none.
            if chosen_piece example; then cat <<LIST
src/main/java/$reference_path/todo/domain/TodoAttachment.java
src/main/java/$reference_path/todo/repository/TodoAttachmentRepository.java
src/main/java/$reference_path/todo/dto/response/TodoAttachmentResponse.java
src/main/resources/db/changelog/changes/0010-todo-attachments.sql
src/test/java/$reference_path/todo/TodoAttachmentsIntegrationTest.java
LIST
            fi
            # The policy admin API's role, unless the metadata piece already brings it.
            if chosen_piece sign-in && ! chosen_piece metadata; then
                echo "src/main/resources/db/changelog/changes/0007-users-admin-role.sql"
            fi
            ;;
        *) die "unknown piece: $1 (core, sign-in, example, metadata, media)" ;;
    esac
}

dependencies_for() {
    case "$1" in
        # Every <dependency> of reference-app/pom.xml appears in exactly one of these (verify.sh checks it).
        core) echo "spring-boot-starter-webmvc, spring-boot-starter-json, spring-boot-starter-validation, spring-boot-starter-security, spring-boot-starter-oauth2-resource-server, spring-boot-starter-data-jpa, spring-boot-starter-liquibase, spring-boot-starter-actuator, postgresql (compile scope: constraint names and the Liquibase connection use the driver's classes), lombok, hibernate-processor (annotation processor), springdoc-openapi-starter-webmvc-ui, resilience4j-spring-boot4, logstash-logback-encoder, spring-boot-micrometer-tracing-brave, micrometer-tracing-bridge-brave, micrometer-registry-prometheus; test: spring-boot-starter-webmvc-test, spring-boot-starter-security-test, spring-boot-starter-data-jpa-test, spring-boot-starter-liquibase-test, spring-boot-starter-actuator-test, spring-boot-micrometer-tracing-test, spring-boot-testcontainers, testcontainers-junit-jupiter, testcontainers-postgresql, archunit-junit5" ;;
        sign-in) echo "spring-boot-starter-data-redis, bucket4j_jdk17-core, bucket4j_jdk17-lettuce" ;;
        example) echo "(none beyond core)" ;;
        metadata) echo "(none beyond core)" ;;
        media) echo "s3 (software.amazon.awssdk), its version from software.amazon.awssdk:bom imported in dependencyManagement; test: the image versity/versitygw (a Testcontainers GenericContainer, no extra dependency)" ;;
    esac
}

dest_for() {
    local relative="$1"
    case "$relative" in
        src/main/java/"$reference_path"/*) echo "src/main/java/$package_path/${relative#src/main/java/$reference_path/}" ;;
        src/test/java/"$reference_path"/*) echo "src/test/java/$package_path/${relative#src/test/java/$reference_path/}" ;;
        *) echo "$relative" ;;
    esac
}

# Without an optional piece, its blocks in shared files are dropped; with it, only the marker lines.
piece_filter_script=""
for optional in sign-in example metadata media admin; do
    if { [ "$optional" = admin ] && admin_chosen; } || chosen_piece "$optional"; then
        piece_filter_script+="/$optional:\\(begin\\|end\\)/d;"
    else
        piece_filter_script+="/$optional:begin/,/$optional:end/d;"
    fi
done
piece_filter() {
    sed "$piece_filter_script"
}

copied=() skipped=()
copy_file() {
    local relative="$1" dest
    dest="$(dest_for "$relative")"
    if [ -e "$target/$dest" ]; then
        skipped+=("$dest")
        return
    fi
    copied+=("$dest")
    $dry_run && return
    mkdir -p "$(dirname "$target/$dest")"
    case "$relative" in
        *.java|*.yml|*.yaml|*.xml|*.properties|.env.example)
            sed "s/${REFERENCE_PACKAGE//./\\.}/$package/g" "$REFERENCE/$relative" | piece_filter > "$target/$dest" ;;
        *) cp "$REFERENCE/$relative" "$target/$dest" ;;
    esac
}

IFS=',' read -r -a chosen <<< "$pieces"
if [[ ",$pieces," == *",core,"* && ",$pieces," != *",sign-in,"* ]]; then
    echo "warning: core's integration tests sign users in through the sign-in piece; without it, adapt them" >&2
fi
if admin_chosen && ! chosen_piece sign-in; then
    echo "warning: the metadata and upload-policy admin APIs need role ADMIN in the token's roles claim: map it from your identity provider" >&2
fi
if chosen_piece media; then
    echo "note: the media piece needs MEDIA_BUCKET and MEDIA_REGION, and S3 credentials from the AWS SDK's default chain (an IAM role in production)" >&2
fi
# Every file any piece can name, whatever was chosen: a directory copy never takes another piece's file.
named_files="$(pieces=core,sign-in,example,metadata,media; for piece in core sign-in example metadata media; do files_for "$piece"; done)"
named_alone() { grep -qxF "$1" <<< "$named_files"; }
for piece in "${chosen[@]}"; do
    while IFS= read -r entry; do
        [ -e "$REFERENCE/$entry" ] || die "the reference has no $entry (piece $piece)"
        if [ -d "$REFERENCE/$entry" ]; then
            while IFS= read -r file; do
                # Copied with the piece that names it, if that piece is chosen.
                named_alone "${file#$REFERENCE/}" || copy_file "${file#$REFERENCE/}"
            done < <(find "$REFERENCE/$entry" -type f | sort)
        else
            copy_file "$entry"
        fi
    done < <(files_for "$piece")
done

# The Liquibase master lists the changesets that were copied, in order; an existing one is kept.
master="src/main/resources/db/changelog/db.changelog-master.yaml"
if [ -e "$target/$master" ]; then
    skipped+=("$master (add the new changesets to it yourself)")
else
    copied+=("$master")
    if ! $dry_run; then
        # The changes directory exists even when no piece brings a changeset (core alone).
        mkdir -p "$target/src/main/resources/db/changelog/changes"
        {
            echo "databaseChangeLog:"
            find "$target/src/main/resources/db/changelog/changes" -name '*.sql' | sort | while IFS= read -r changeset; do
                echo "  - include:"
                echo "      file: changes/$(basename "$changeset")"
                echo "      relativeToChangelogFile: true"
            done
        } > "$target/$master"
    fi
fi

if $dry_run; then
    echo "DRY RUN: nothing was written. Would copy ${#copied[@]} file(s) into $target (package $package)"
else
    echo "copied ${#copied[@]} file(s) into $target (package $package)"
fi
if [ ${#skipped[@]} -gt 0 ]; then
    echo "skipped ${#skipped[@]} existing file(s), left as they are:"
    printf '  %s\n' "${skipped[@]}"
fi
echo "dependencies these pieces need (add after the owner approves them):"
for piece in "${chosen[@]}"; do
    echo "  $piece: $(dependencies_for "$piece")"
done
echo "then: add @ConfigurationPropertiesScan to the application class; set spring.application.name, and DB_NAME"
echo "      in .env.example to the same name; delete Initializr's application.properties (application.yml"
echo "      replaces it) and its <App>ApplicationTests (a bare @SpringBootTest with no database: the copied"
echo "      test base replaces it); new changesets continue after the highest copied number."
