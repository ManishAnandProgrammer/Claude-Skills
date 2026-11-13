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
#              persistence base, config files, test base, ArchUnit
#     sign-in  user module (signup, login, tokens) + Redis rate limiting
#     example  the golden crud-gen module (todo lists and todos), for reference only
# Shared files (ErrorCode, messages, limits) mark the example module's entries with
# "example:begin" / "example:end" comment lines: without the example piece those blocks are left out.
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
        -h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
compose.yaml
.env.example
Dockerfile
.dockerignore
LIST
            ;;
        sign-in) cat <<LIST
src/main/java/$reference_path/user
src/main/resources/db/changelog/changes/0001-users-and-refresh-tokens.sql
src/test/java/$reference_path/user
LIST
            ;;
        example) cat <<LIST
src/main/java/$reference_path/todo
src/main/resources/db/changelog/changes/0002-todo-lists.sql
src/main/resources/db/changelog/changes/0003-todos.sql
src/main/resources/db/changelog/changes/0004-tags.sql
src/main/resources/db/changelog/changes/0005-time-entries.sql
src/test/java/$reference_path/todo
LIST
            ;;
        *) die "unknown piece: $1 (core, sign-in, example)" ;;
    esac
}

dependencies_for() {
    case "$1" in
        core) echo "spring-boot-starter-{webmvc,json,validation,security,oauth2-resource-server,data-jpa,liquibase,actuator}, postgresql, lombok, hibernate-processor (annotation processor), springdoc-openapi-starter-webmvc-ui, resilience4j-spring-boot4, logstash-logback-encoder, micrometer-tracing-bridge-brave, micrometer-registry-prometheus; test: spring-boot-starter-{webmvc,security,data-jpa}-test, spring-boot-testcontainers, testcontainers-{junit-jupiter,postgresql}, archunit-junit5" ;;
        sign-in) echo "spring-boot-starter-data-redis, bucket4j_jdk17-core, bucket4j_jdk17-lettuce" ;;
        example) echo "(none beyond core)" ;;
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

with_example=false
[[ ",$pieces," == *",example,"* ]] && with_example=true

# Without the example piece, its blocks in shared files are dropped; with it, only the marker lines.
example_filter() {
    if $with_example; then
        sed '/example:\(begin\|end\)/d'
    else
        sed '/example:begin/,/example:end/d'
    fi
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
        *.java|*.yml|*.yaml|*.xml|*.properties)
            sed "s/${REFERENCE_PACKAGE//./\\.}/$package/g" "$REFERENCE/$relative" | example_filter > "$target/$dest" ;;
        *) cp "$REFERENCE/$relative" "$target/$dest" ;;
    esac
}

IFS=',' read -r -a chosen <<< "$pieces"
if [[ ",$pieces," == *",core,"* && ",$pieces," != *",sign-in,"* ]]; then
    echo "warning: core's integration tests sign users in through the sign-in piece; without it, adapt them" >&2
fi
for piece in "${chosen[@]}"; do
    while IFS= read -r entry; do
        [ -e "$REFERENCE/$entry" ] || die "the reference has no $entry (piece $piece)"
        if [ -d "$REFERENCE/$entry" ]; then
            while IFS= read -r file; do
                copy_file "${file#$REFERENCE/}"
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
        mkdir -p "$(dirname "$target/$master")"
        {
            echo "databaseChangeLog:"
            find "$target/src/main/resources/db/changelog/changes" -name '*.sql' 2>/dev/null | sort | while IFS= read -r changeset; do
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
echo "then: add @ConfigurationPropertiesScan to the application class; set spring.application.name;"
echo "      remove an Initializr application.properties if present (application.yml replaces it)."
