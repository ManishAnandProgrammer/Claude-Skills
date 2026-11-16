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
#     tenancy-schema
#              multi-tenancy, one PostgreSQL schema per tenant (needs sign-in): the tenant in the
#              verified token, the transaction-local search_path hook, the tenant register and platform
#              staff under /platform/** with their own tokens, impersonation and audited direct edits,
#              memberships, provisioning and the deploy-time tenant migrations, two changelogs
#              (platform.yaml, tenant.yaml)
#     tenancy-row
#              multi-tenancy, one schema with a tenant_id and row-level security on every tenant table
#              (needs sign-in; not with tenancy-schema): the same platform and token parts, the
#              transaction-local app.tenant_id hook, Hibernate's @TenantId through TenantOwnedEntity,
#              the RLS policies in the changesets, provisioning as an activation after the approval
#     tenant-registration
#              with a tenancy piece: the public POST /tenant-registrations (a PENDING tenant that staff
#              approve); without it, only staff create tenants
#     session-version
#              with sign-in, opt-in: every access token carries a session version (claim sv), checked on
#              every request against Redis (the database when Redis is down); every access change raises it
#              in its own transaction, so older tokens are refused at once (401 SESSION_VERSION_STALE), and
#              DELETE /users/me/sessions signs out everywhere. Per account, or per membership with tenancy
#   sign-in (account suspension), metadata, media and the tenancy pieces have admin endpoints: each brings
#   the ADMIN role and the test base's newAdmin() (the "admin" markers).
#
# Where files come from: reference-app/ is a plain application, without tenancy. A tenancy mode adds
# layers over it, each mirroring the reference's tree: overlays/tenancy (what every mode shares) and
# overlays/tenancy-schema (schema per tenant only) or overlays/tenancy-row (row mode only). A layer's
# file that the reference (or an earlier layer) also has replaces it in the copy, and only when that
# file is copied; overlays/replaced.sha256 pins the version each replacement was written against
# (scripts/overlay-replacements.sh, run by verify.sh). Every other layer file is new and arrives with
# its mode.
#
# Shared files (ErrorCode, messages, limits, db.yml, compose.yaml, .env.example, Role, test base,
# ArchUnit) mark a piece's entries with "<piece>:begin" / "<piece>:end" comment lines (sign-in,
# example, metadata, media, admin, tenancy, tenancy-schema, tenancy-row, tenant-registration,
# session-version): without that piece
# those blocks are left out; blocks may nest (a media block inside an example block arrives only with
# both). A block may hold "<piece>:else": its lines after that marker are commented out in the
# reference and replace the block's first part without the piece (uncommented: "// ", "# ", "-- " or
# "<!-- -->" dropped); with the piece they are left out. For code that differs with the piece, not only
# grows. The reference runs without tenancy, so in reference files a tenancy block is the other way
# round: its lines are commented out (the reference compiles and reads without them) and arrive
# uncommented with the piece; its ":else" part is the reference's own code, left out with the piece.
# In layer files every marker keeps the plain meaning. A file a piece names on its own belongs to that
# piece, even inside a directory another piece copies (sign-in's rate limiter in core's common/ and
# config/, media's todo attachments in the example's todo/). Sample content of an optional piece
# arrives only with the example too: the metadata piece's sample keys, the media piece's purpose
# TODO_ATTACHMENT, and their tests (the media piece's own endpoint tests run against whichever purpose
# comes first, and are skipped until the project declares one).
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

# The ADMIN role (Role.ADMIN, its changeset, newAdmin()) comes with the first piece that has admin endpoints:
# sign-in's account suspension already has one.
admin_chosen() { chosen_piece sign-in || chosen_piece metadata || chosen_piece media || tenancy_chosen; }

# The tenancy part every mode shares (overlays/tenancy, the "tenancy" markers): schema per tenant or row mode.
tenancy_chosen() { chosen_piece tenancy-schema || chosen_piece tenancy-row; }

# The layers a copy is made of, in order: a later layer's file replaces an earlier one's.
layers="reference-app"
if tenancy_chosen; then
    layers+=" overlays/tenancy"
fi
if chosen_piece tenancy-schema; then
    layers+=" overlays/tenancy-schema"
fi
if chosen_piece tenancy-row; then
    layers+=" overlays/tenancy-row"
fi
SHARED="overlays/tenancy"
SCHEMA="overlays/tenancy-schema"
ROW="overlays/tenancy-row"

# What every tenancy mode copies from overlays/tenancy, beside the mode's own layer.
shared_tenancy_files() {
    echo "$SHARED/src"
    # The cross-tenant tests of the example's entities (crud-gen generates one per entity).
    if chosen_piece example; then
        echo "$SHARED/src/test/java/$reference_path/todo/TodoTenantIsolationIntegrationTest.java"
        # The todo's requester is a member: its name comes from user.api.Members.
        echo "$SHARED/src/main/java/$reference_path/todo/dto/response/TodoRequesterResponse.java"
        echo "$SHARED/src/test/java/$reference_path/todo/TodoRequesterTenancyIntegrationTest.java"
    fi
    # Staff editing a tenant's configuration directly: the golden cross-tenant edit.
    if chosen_piece metadata; then cat <<LIST
$SHARED/src/main/java/$reference_path/metadata/service/PlatformMetadataService.java
$SHARED/src/main/java/$reference_path/metadata/controller/PlatformMetadataController.java
$SHARED/src/main/java/$reference_path/metadata/controller/api/PlatformMetadataApi.java
LIST
        if chosen_piece example; then
            echo "$SHARED/src/test/java/$reference_path/metadata/PlatformMetadataIntegrationTest.java"
        fi
        # Counters count per tenant.
        echo "$SHARED/src/test/java/$reference_path/metadata/CounterTenantIntegrationTest.java"
    fi
}

# Files per piece, relative to reference-app/, or to the repository when they start with overlays/.
# Directories are copied recursively.
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
docs/local-dev.md
LIST
            ;;
        sign-in) cat <<LIST
src/main/java/$reference_path/user
src/main/java/$reference_path/common/ratelimit/RateLimit.java
src/main/java/$reference_path/common/ratelimit/RateLimitProperties.java
src/main/java/$reference_path/common/ratelimit/RedisRateLimiter.java
src/main/java/$reference_path/config/RedisClientConfig.java
src/main/resources/db/changelog/changes/0001-users-and-refresh-tokens.sql
src/main/resources/db/changelog/changes/0007-users-admin-role.sql
src/main/resources/db/changelog/changes/0027-users-status.sql
src/main/java/$reference_path/common/validation/PasswordPolicy.java
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
src/main/resources/db/changelog/changes/0024-tags-status-newest.sql
src/main/resources/db/changelog/changes/0025-todos-requester.sql
src/main/resources/db/changelog/changes/0029-newest-by-created-at.sql
src/test/java/$reference_path/todo
src/test/java/$reference_path/planning
src/test/resources/db/upgrade/previous-release-seed.sql
LIST
            # The metadata piece's sample configuration and the tests that exercise the mechanism through it.
            if chosen_piece metadata; then cat <<LIST
src/main/java/$reference_path/metadata/api/TodoListSettings.java
src/main/resources/db/changelog/changes/0014-todo-list-defaults.sql
src/main/resources/db/changelog/changes/0026-todo-numbers.sql
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
            # With tenancy, the jobs' loop over the tenants (the jobs themselves are replacements).
            if tenancy_chosen; then
                echo "overlays/tenancy/src/test/java/$reference_path/media/service/MediaJobsPerTenantTest.java"
                echo "overlays/tenancy/src/test/java/$reference_path/media/mapper/MediaKeysPerTenantTest.java"
            fi
            ;;
        tenancy-schema)
            # The part every tenancy mode shares, then schema per tenant's own.
            shared_tenancy_files
            echo "$SCHEMA/src"
            echo "$SCHEMA/docs"
            echo "$SCHEMA/docker"
            # The example's owners reference the tenant's members instead of the platform's users; a tenant
            # schema's rows at the previous release, for the upgrade path (support/PreviousReleaseDatabase).
            if chosen_piece example; then
                echo "$SCHEMA/src/main/resources/db/changelog/changes/0018-owners-reference-members.sql"
                echo "$SCHEMA/src/test/resources/db/upgrade/previous-release-tenant-seed.sql"
            fi
            ;;
        tenancy-row)
            # The part every tenancy mode shares, then row mode's own.
            shared_tenancy_files
            echo "$ROW/src"
            echo "$ROW/docs"
            echo "$ROW/docker"
            # Upload policies' starting limits per tenant, and the module listener that seeds them.
            # The example's fixed metadata key in every new tenant (0014 seeds the existing ones).
            # The todos' display-number counter in every new tenant (0026 gives it to the existing ones).
            if chosen_piece metadata && chosen_piece example; then
                echo "$ROW/src/main/java/$reference_path/todo/service/TodoListDefaultsSeed.java"
                echo "$ROW/src/main/java/$reference_path/todo/service/TodoNumberSeed.java"
            fi
            # The tenant tables' column defaults dropped, and their replica identities set to a key with
            # tenant_id: only with a piece that brings tenant tables.
            if chosen_piece example || chosen_piece metadata || chosen_piece media; then
                echo "$ROW/src/main/resources/db/changelog/changes/0016-tenant-id-no-default.sql"
                echo "$ROW/src/main/resources/db/changelog/changes/0019-replica-identity.sql"
            fi
            # The example's owners reference the tenant's members instead of the platform's users, and
            # get dependency statistics with their tenant, with the row-mode index test that needs them.
            if chosen_piece example; then
                echo "$ROW/src/main/resources/db/changelog/changes/0018-owners-reference-members.sql"
                echo "$ROW/src/main/resources/db/changelog/changes/0023-owner-tenant-statistics.sql"
                echo "$ROW/src/test/java/$reference_path/planning/PlanRowIndexIntegrationTest.java"
            fi
            if chosen_piece media; then
                echo "$ROW/src/main/java/$reference_path/media/api/UploadLimits.java"
                if chosen_piece example; then
                    echo "$ROW/src/main/java/$reference_path/todo/service/TodoAttachmentPolicySeed.java"
                fi
            fi
            # The todo module's seeds against their backfills (the attachments' policy, the defaults, the counter).
            if chosen_piece example && { chosen_piece media || chosen_piece metadata; }; then
                echo "$ROW/src/test/java/$reference_path/todo/TodoSeedsBackfillIntegrationTest.java"
            fi
            ;;
        tenant-registration) cat <<LIST
$SHARED/src/main/java/$reference_path/user/controller/TenantRegistrationController.java
$SHARED/src/main/java/$reference_path/user/controller/api/TenantRegistrationApi.java
$SHARED/src/main/java/$reference_path/user/dto/request/TenantRegistrationRequest.java
$SHARED/src/main/java/$reference_path/user/service/TenantRegistrationService.java
$SHARED/src/test/java/$reference_path/tenant/TenantRegistrationIntegrationTest.java
LIST
            ;;
        session-version)
            # The check (core's common/ and config/ hold it, named here), the cache and its tests.
            cat <<LIST
src/main/java/$reference_path/common/security/SessionVersions.java
src/main/java/$reference_path/common/security/SessionVersionFilter.java
src/main/java/$reference_path/config/SessionVersionOpenApiCustomizer.java
src/main/java/$reference_path/user/service/SessionVersionService.java
src/main/java/$reference_path/user/service/SessionVersionProperties.java
src/main/java/$reference_path/user/service/SessionVersionsRaised.java
src/test/java/$reference_path/common/security/SessionVersionFilterIntegrationTest.java
src/test/java/$reference_path/user/SessionVersionIntegrationTest.java
src/test/java/$reference_path/user/service/SessionVersionServiceIntegrationTest.java
LIST
            # The number: the account's without tenancy, the membership's with it (and the tenant's
            # suspension raises its members' numbers).
            if tenancy_chosen; then cat <<LIST
$SHARED/src/main/resources/db/changelog/changes/0022-memberships-session-version.sql
$SHARED/src/main/java/$reference_path/user/dto/internal/MembershipSessionVersion.java
$SHARED/src/main/java/$reference_path/tenant/api/TenantDeactivated.java
$SHARED/src/test/java/$reference_path/user/SessionVersionTenancyIntegrationTest.java
LIST
            else
                echo "src/main/resources/db/changelog/changes/0021-users-session-version.sql"
            fi
            ;;
        *) die "unknown piece: $1 (core, sign-in, example, metadata, media, tenancy-schema, tenancy-row, tenant-registration, session-version)" ;;
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
        tenancy-schema) echo "(none beyond core: Hibernate's multi-tenancy and Liquibase, already there)" ;;
        tenancy-row) echo "(none beyond core: Hibernate's multi-tenancy and @TenantId, already there)" ;;
        tenant-registration) echo "(none beyond sign-in)" ;;
        session-version) echo "(none beyond core and sign-in: Redis and Resilience4j's circuit breaker, already there)" ;;
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

# Without an optional piece, its blocks in shared files are dropped (an ":else" part kept, uncommented);
# with it, only the marker lines (and an ":else" part) go. In reference files a tenancy block is
# commented out instead: kept uncommented with the piece, dropped without it.
kept_markers=","
for optional in sign-in example metadata media admin tenancy tenancy-schema tenancy-row tenant-registration session-version; do
    if { [ "$optional" = admin ] && admin_chosen; } || { [ "$optional" = tenancy ] && tenancy_chosen; } || chosen_piece "$optional"; then
        kept_markers+="$optional,"
    fi
done
# $1: "reference" for a reference-app file, "layer" for an overlay's.
# A blank line on each side of a dropped block would leave two in a row: the second is left out.
piece_filter() {
    KEPT="$kept_markers" SOURCE="$1" perl -ne '
        BEGIN { @open = (); $last_blank = 0; $dropped = 0; }
        if (/\b(sign-in|example|metadata|media|admin|tenancy-schema|tenancy-row|tenancy|tenant-registration|session-version):(begin|else|end)\b/) {
            my ($piece, $marker) = ($1, $2);
            my $kept = index($ENV{KEPT}, ",$piece,") >= 0;
            my $commented = $ENV{SOURCE} eq "reference" && $piece =~ /^tenan/;
            if ($marker eq "begin") { push @open, $kept ? ($commented ? "uncomment" : "keep") : "drop"; }
            elsif ($marker eq "else") { $open[-1] = $kept ? "drop" : ($commented ? "keep" : "uncomment"); }
            else { pop @open; }
            next;
        }
        if (grep { $_ eq "drop" } @open) { $dropped = 1; next; }
        if (grep { $_ eq "uncomment" } @open) {
            s{^(\s*)<!-- ?(.*?) ?-->[ \t]*$}{$1$2} or s{^(\s*)(?://|#|--) ?}{$1};
            # A bare "//" (a blank line in a commented block) leaves only its indent: no trailing spaces.
            s{^[ \t]+$}{};
        }
        my $blank = /^\s*$/;
        next if $blank && $last_blank && $dropped;
        $last_blank = $blank ? 1 : 0;
        $dropped = 0 unless $blank;
        print;
    '
}

# A file's path in the project (and in the reference): its source path without the layer.
project_path() {
    case "$1" in
        reference-app/*) echo "${1#reference-app/}" ;;
        overlays/*) local rest="${1#overlays/*/}"; echo "$rest" ;;
    esac
}
# The chosen layer that has the last say on a project path.
source_for() {
    local relative="$1" layer found=""
    for layer in $layers; do
        if [ -f "$REPO/$layer/$relative" ]; then
            found="$layer/$relative"
        fi
    done
    echo "$found"
}
# True when an earlier layer than the file's own has the same project path: the file replaces that one.
replaces_earlier() {
    local source="$1" relative layer
    relative="$(project_path "$source")"
    for layer in $layers; do
        [[ "$source" == "$layer/"* ]] && return 1
        [ -f "$REPO/$layer/$relative" ] && return 0
    done
    return 1
}

copied=() skipped=()
copy_file() {
    local relative dest source mode
    relative="$(project_path "$1")"
    dest="$(dest_for "$relative")"
    if [ -e "$target/$dest" ]; then
        skipped+=("$dest")
        return
    fi
    copied+=("$dest")
    $dry_run && return
    source="$(source_for "$relative")"
    mode=layer
    if [[ "$source" == reference-app/* ]]; then
        mode=reference
    fi
    mkdir -p "$(dirname "$target/$dest")"
    case "$relative" in
        *.java|*.yml|*.yaml|*.xml|*.properties|*.sql|*.md|*.sh|.env.example)
            sed "s/${REFERENCE_PACKAGE//./\\.}/$package/g" "$REPO/$source" | piece_filter "$mode" > "$target/$dest" ;;
        *) cp "$REPO/$source" "$target/$dest" ;;
    esac
    if [ -x "$REPO/$source" ]; then
        chmod +x "$target/$dest"
    fi
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
if chosen_piece tenancy-schema && chosen_piece tenancy-row; then
    die "tenancy-schema and tenancy-row are two answers to one question: choose one"
fi
if tenancy_chosen && ! chosen_piece sign-in; then
    die "a tenancy piece needs sign-in: memberships, the tenant claim and the platform's login live in the user module"
fi
if chosen_piece tenant-registration && ! tenancy_chosen; then
    die "tenant-registration needs a tenancy piece (tenancy-schema or tenancy-row)"
fi
if chosen_piece session-version && ! chosen_piece sign-in; then
    die "session-version needs sign-in: it checks the tokens sign-in issues"
fi
if chosen_piece tenancy-schema; then
    echo "note: tenancy needs the database roles and the platform schema before the first start (docs/tenancy-runbook.md; compose.yaml creates them locally), and DB_OWNER_USER / DB_OWNER_PASSWORD" >&2
fi
if chosen_piece tenancy-row; then
    echo "note: tenancy needs the database roles before the first start (docs/tenancy-runbook.md; compose.yaml creates them locally), and DB_OWNER_USER / DB_OWNER_PASSWORD" >&2
fi
# Every file any piece can name, whatever was chosen: a directory copy never takes another piece's file.
all_pieces="core sign-in example metadata media tenancy-schema tenancy-row tenant-registration session-version"
with_reference_prefix() { sed -e '/^overlays\//!s|^|reference-app/|'; }
named_files="$(pieces="${all_pieces// /,}"; for piece in $all_pieces; do files_for "$piece"; done | with_reference_prefix)"
# Core files a tenancy mode replaces: its connection hook borrows lazily, so the lazy proxy goes; a person in
# a tenant is a member (user.api.Members), so the accounts' facade goes too.
replaced_by_tenancy="reference-app/src/main/java/$reference_path/config/LazyConnectionConfig.java
reference-app/src/test/java/$reference_path/config/LazyConnectionIntegrationTest.java
reference-app/src/main/java/$reference_path/user/api/Users.java"
replaced() { tenancy_chosen && grep -qxF "$1" <<< "$replaced_by_tenancy"; }
named_alone() { grep -qxF "$1" <<< "$named_files"; }
for piece in "${chosen[@]}"; do
    while IFS= read -r entry; do
        [ -e "$REPO/$entry" ] || die "the repository has no $entry (piece $piece)"
        if [ -d "$REPO/$entry" ]; then
            while IFS= read -r file; do
                file="${file#$REPO/}"
                # Copied with the piece that names it, if that piece is chosen; a replacement only with
                # the file it replaces.
                named_alone "$file" || replaced "$file" || replaces_earlier "$file" || copy_file "$file"
            done < <(find "$REPO/$entry" -type f | sort)
        else
            copy_file "$entry"
        fi
    done < <(files_for "$piece" | with_reference_prefix)
done

# The Liquibase master lists the changesets that were copied, in order; an existing one is kept.
# Schema per tenant has two masters, copied with the piece (platform.yaml, tenant.yaml): they are
# rewritten here to list exactly the copied changesets, the platform's in the first, every other in
# the second. Row mode has one master with the tenancy base first (every tenant table references
# tenants, and the app role's default privileges, 0013, then cover each tenant table), then every other
# changeset in number order, so a release is a prefix of the master (the upgrade path's previous release,
# support/PreviousReleaseDatabase). A later platform changeset that tenant tables don't need goes in
# platform_changesets only, never in first_changesets.
first_changesets="0001-users-and-refresh-tokens.sql 0007-users-admin-role.sql 0012-tenancy.sql 0013-tenant-schemas.sql 0013-row-security.sql 0015-platform-users-read-only.sql 0020-tenant-members-list.sql 0021-users-session-version.sql 0022-memberships-session-version.sql"
platform_changesets="$first_changesets 0027-users-status.sql 0028-platform-users-deactivation.sql 0030-platform-list-indexes.sql"
# $1: platform, tenant, first, rest or all; the copied changesets of that kind, in order.
changesets_of() {
    local want="$1" changeset name
    while IFS= read -r changeset; do
        name="$(basename "$changeset")"
        if { [ "$want" = platform ] && [[ " $platform_changesets " == *" $name "* ]]; } \
            || { [ "$want" = tenant ] && [[ " $platform_changesets " != *" $name "* ]]; } \
            || { [ "$want" = first ] && [[ " $first_changesets " == *" $name "* ]]; } \
            || { [ "$want" = rest ] && [[ " $first_changesets " != *" $name "* ]]; } || [ "$want" = all ]; then
            echo "$name"
        fi
    done < <(find "$target/src/main/resources/db/changelog/changes" -name '*.sql' | sort)
}
# $3: platform, tenant, all, or platform-first (row mode: the tenancy base, then every other).
write_master() {
    local file="$1" header="$2" want="$3" includes="" names name
    if [ "$want" = platform-first ]; then
        names="$(changesets_of first; changesets_of rest)"
    else
        names="$(changesets_of "$want")"
    fi
    while IFS= read -r name; do
        if [ -n "$name" ]; then
            includes+="  - include:"$'\n'"      file: changes/$name"$'\n'"      relativeToChangelogFile: true"$'\n'
        fi
    done <<< "$names"
    {
        if [ -n "$header" ]; then
            printf '%s\n' "$header"
        fi
        if [ -n "$includes" ]; then
            echo "databaseChangeLog:"
            printf '%s' "$includes"
        else
            # No changeset yet (a tenant changelog before the first tenant table): an empty list, not null.
            echo "databaseChangeLog: []"
        fi
    } > "$target/$file"
}
# The changes directory exists even when no piece brings a changeset (core alone).
$dry_run || mkdir -p "$target/src/main/resources/db/changelog/changes"
if chosen_piece tenancy-schema; then
    for master in platform tenant; do
        if ! $dry_run && [[ " ${copied[*]} " == *" src/main/resources/db/changelog/$master.yaml "* ]]; then
            header="$(grep '^#' "$REPO/$SCHEMA/src/main/resources/db/changelog/$master.yaml")"
            write_master "src/main/resources/db/changelog/$master.yaml" "$header" "$master"
        fi
    done
else
    master="src/main/resources/db/changelog/db.changelog-master.yaml"
    if [ -e "$target/$master" ]; then
        skipped+=("$master (add the new changesets to it yourself)")
    else
        copied+=("$master")
        if ! $dry_run && chosen_piece tenancy-row; then
            write_master "$master" "" platform-first
        elif ! $dry_run; then
            write_master "$master" "" all
        fi
    fi
fi

# Initializr's .gitignore misses the local secrets: .env (what .env.example asks for) and the JVM's
# diagnostics, which hold tokens and personal data (SEC-04). Appended, never overwritten.
if chosen_piece core; then
    ignored=""
    for pattern in .env '*.hprof' '*.jfr' 'hs_err_pid*.log'; do
        grep -qxF "$pattern" "$target/.gitignore" 2>/dev/null || ignored+="$pattern"$'\n'
    done
    if [ -n "$ignored" ]; then
        copied+=(".gitignore (appended: $(echo -n "$ignored" | tr '\n' ' '))")
        $dry_run || printf '\n### Local secrets (bootstrap) ###\n%s' "$ignored" >> "$target/.gitignore"
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
# Initializr writes its own (postgres:latest, nothing else) when Testcontainers is ticked; kept, it
# leaves every integration test without Redis and the media store.
if [[ " ${skipped[*]} " == *" src/test/java/$package_path/TestcontainersConfiguration.java "* ]]; then
    echo "warning: src/test/java/$package_path/TestcontainersConfiguration.java existed and was kept: if it is" >&2
    echo "         Initializr's, delete it and run again (the reference's starts PostgreSQL 18, Redis and the media store)" >&2
fi
echo "dependencies these pieces need (add after the owner approves them):"
for piece in "${chosen[@]}"; do
    echo "  $piece: $(dependencies_for "$piece")"
done
echo "then: add @ConfigurationPropertiesScan to the application class; set spring.application.name, and DB_NAME"
echo "      in .env.example to the same name; delete Initializr's application.properties (application.yml"
echo "      replaces it), its <App>ApplicationTests (a bare @SpringBootTest with no database: the copied"
echo "      test base replaces it) and, before copying, its TestcontainersConfiguration; the project's own"
echo "      changesets start at 0100 (the pieces own 0001-0099)."
