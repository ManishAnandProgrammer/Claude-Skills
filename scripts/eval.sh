#!/usr/bin/env bash
# Evaluates the model-driven half of a skills release (evals/README.md): crud-gen generates code that builds, stays in
# its lane and passes house-review; house-review finds what the rules forbid. scripts/verify.sh proves the reference
# and the bootstrap copies; this proves the skills that read them.
#
# Every model run is headless `claude -p` with an isolated HOME that holds only this worktree's crud-gen and
# house-review (symlinks), the reference, the owner's login (a symlink, never read) and the Maven repository. The
# release under test is a snapshot of the working tree taken when the run starts (snapshot below).
#
#   scripts/eval.sh [--case <glob>[,<glob>...]] [--budget <usd per model run>] [--model <model>] [--keep]
#   scripts/eval.sh --check [--case ...]        render every fixture and apply every patch, compile; no model run
#   scripts/eval.sh --prepare-review <dir>      a review copy at its baseline commit, to write a new defect.patch in
#
#   --case     globs over the case ids (g1-room, r1-missing-requires, c1-clean-control); default every case
#   --budget   --max-budget-usd of each model run (default 15)
#   --keep     keep every work copy (by default a passed case's copy is deleted, a failed one's kept)
#   --always-review  run gate 3 even when gate 1 is red
#
# Results: evals/results/<timestamp>/ (gitignored): per case the transcripts (stream-json), each run's record
# (cost, time, turns, tools, permission denials, questions), each gate's JSON, and summary.md over all cases.
# Docker must be reachable (Testcontainers): run as `sg docker -c scripts/eval.sh` when it isn't.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
EVALS="$REPO/evals"
# The release under test: set by snapshot() to the run's frozen copy of the working tree.
SKILLS="" REFERENCE=""
GATES=(python3 -I "$EVALS/lib/gates.py")
export JAVA_HOME="${JAVA_HOME:-/home/manu/.jdks/temurin-25.0.5}"
GEN_TIMEOUT="${EVAL_GEN_TIMEOUT:-10800}"
REVIEW_TIMEOUT="${EVAL_REVIEW_TIMEOUT:-3600}"
REVIEW_PACKAGE="com.acme.review"
REVIEW_PIECES="core,sign-in,example"

usage() { sed -n '2,/^set -euo/{/^#/p}' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

globs="*" budget=15 model="" check=false keep=false prepare="" always_review=false
while [ $# -gt 0 ]; do
    case "$1" in
        --case) globs="${2:?--case needs a glob}"; shift 2 ;;
        --budget) budget="${2:?--budget needs an amount}"; shift 2 ;;
        --model) model="${2:?--model needs a model}"; shift 2 ;;
        --check) check=true; shift ;;
        --keep) keep=true; shift ;;
        --always-review) always_review=true; shift ;;
        --prepare-review) prepare="${2:?--prepare-review needs a directory}"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "unknown option: $1" >&2; usage 2 ;;
    esac
done

die() { echo "eval: $*" >&2; exit 1; }

# ---- the release under test --------------------------------------------------------------------------------------

# The release under test, frozen for the whole run: the working tree's skills, reference, overlays, scripts, guides and
# docs (uncommitted and untracked files included, ignored ones such as target/ not), copied once. Every copy is
# rendered from it and every isolated HOME links into it, so an edit made in the worktree meanwhile doesn't change the
# run, and a model run that writes through a link writes into the snapshot, never into the worktree.
SNAPSHOT_PATHS=(crud-gen house-review reference-app overlays scripts guides docs)
snapshot() {
    case "$1" in /home/*) die "the work directory must lie outside /home (the model runs may not write there): set TMPDIR" ;; esac
    SKILLS="$1" REFERENCE="$1/reference-app"
    mkdir -p "$SKILLS"
    git -C "$REPO" ls-files -co --exclude-standard -z -- "${SNAPSHOT_PATHS[@]}" \
        | (cd "$REPO" && tar --null --ignore-failed-read -T - -cf - 2> /dev/null) | tar -xf - -C "$SKILLS"
    [ -x "$SKILLS/scripts/bootstrap-copy.sh" ] && [ -f "$REFERENCE/pom.xml" ] || die "the snapshot of $REPO is incomplete"
}

# What was tested: the commit and the git tree of each snapshot path, uncommitted changes included (verify.sh's
# fingerprint, over these paths).
release_record() {
    # shellcheck source=lib/fingerprint.sh
    . "$REPO/scripts/lib/fingerprint.sh"
    VERIFIED_PATHS=("${SNAPSHOT_PATHS[@]}")
    {
        echo "commit $(git -C "$REPO" rev-parse HEAD) ($(git -C "$REPO" branch --show-current))"
        paste <(printf '%s\n' "${SNAPSHOT_PATHS[@]}") <(working_fingerprint "$REPO")
        git -C "$REPO" status --short -- "${SNAPSHOT_PATHS[@]}" | sed 's/^/uncommitted: /'
    } > "$1"
}

# ---- rendering a project copy ------------------------------------------------------------------------------------

# Fills a profile template's placeholders for this copy.
render_profile() {
    local template="$1" out="$2" package="$3" app="$4" pieces="$5" modules="$6" module_lines="$7" aggregates="$8"
    local authorization=permissions media_api=none media=none metadata=none token="short-lived(15)" audited=none
    local offset_lists=none cursor_lists="all lists not in offsetLists" example_conventions=""
    [[ ",$pieces," == *",roles,"* ]] && authorization=roles-piece
    [[ ",$pieces," == *",media,"* ]] && media_api="$package.media.api" media=on
    [[ ",$pieces," == *",metadata,"* ]] && metadata=on
    [[ ",$pieces," == *",session-version,"* ]] && token=session-version
    [[ ",$pieces," == *",change-history,"* ]] && audited='`users (password_hash excluded)`'
    if [[ ",$pieces," == *",example,"* ]]; then
        offset_lists='`TodoList`, `Tag`: admin-style screens that need page numbers and a total'
        cursor_lists='`Todo`, `TimeEntry`, `Plan`, dropdowns'
        example_conventions='| A tag'"'"'s delete cascades to its `todo_tags` rows (`fk_todo_tags_tag_id_owner_id ... ON DELETE CASCADE`, the tag side only) | error-contract `*_IN_USE`, DATA-06 | Owner'"'"'s rule: only the label is lost, never a todo; documented in `TagApi` |
| The todo list costs two statements per batch: the batch, then one query for the tags of all its rows | crud-gen tests.md row 21 (cursor lists: 1) | DATA-26'"'"'s two steps: list rows carry a many-to-many without a collection fetch |'
    fi
    local release
    release="$(git -C "$REPO" rev-parse --short HEAD)$(git -C "$REPO" diff --quiet HEAD -- reference-app overlays || echo '-dirty')"
    PIECES="$pieces" ROOT_PACKAGE="$package" APP="$app" MODULE_NAMES="$modules" MODULE_LINES="$module_lines" \
    AGGREGATES="$aggregates" AUTHORIZATION="$authorization" MEDIA_API="$media_api" MEDIA="$media" METADATA="$metadata" \
    TOKEN_REVOCATION="$token" AUDITED_TABLES="$audited" OFFSET_LISTS="$offset_lists" CURSOR_LISTS="$cursor_lists" \
    EXAMPLE_CONVENTIONS="$example_conventions" SKILLS_RELEASE="$release" \
        perl -pe 's/\@([A-Z_]+)\@/exists $ENV{$1} ? $ENV{$1} : $&/ge' "$template" > "$out"
}

# A fresh project as a user starts one (bootstrap.md's "No project yet", as verify.sh builds its copies): an
# application class, then bootstrap-copy.sh --new-project, which writes the wrapper, the ignore files and the pom with
# the coverage minimums a bare copy of these pieces holds (scripts/coverage-minimums); then the profiles.
render_copy() {
    local dir="$1" package="$2" app="$3" pieces="$4" modules="$5" module_lines="$6" aggregates="$7"
    local path="${package//.//}" class
    class="$(tr '[:lower:]' '[:upper:]' <<< "${app:0:1}")${app:1}Application"
    mkdir -p "$dir/src/main/java/$path" "$dir/.claude"
    cat > "$dir/src/main/java/$path/$class.java" <<JAVA
package $package;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class $class {

    public static void main(String[] args) {
        SpringApplication.run($class.class, args);
    }
}
JAVA
    "$SKILLS/scripts/bootstrap-copy.sh" --new-project "$app" --target "$dir" --package "$package" --with "$pieces" > "$dir.copy.log" 2>&1 \
        || { tail -20 "$dir.copy.log" >&2; die "bootstrap-copy.sh failed for $dir"; }
    sed -i "s/\${APP_NAME:reference}/\${APP_NAME:$app}/" "$dir/src/main/resources/application.yml"
    grep -q "APP_NAME:$app" "$dir/src/main/resources/application.yml" || die "could not rename the application in $dir"
    render_profile "$EVALS/lib/profiles/crud-profile.md" "$dir/.claude/crud-profile.md" "$package" "$app" "$pieces" "$modules" "$module_lines" "$aggregates"
    render_profile "$EVALS/lib/profiles/review-profile.md" "$dir/.claude/review-profile.md" "$package" "$app" "$pieces" "$modules" "$module_lines" "$aggregates"
}

commit_all() {
    git -C "$1" add -A
    git -C "$1" commit -q -m "$2"
}

init_repo() {
    git -C "$1" init -q -b main
    git -C "$1" config user.name "eval harness"
    git -C "$1" config user.email "eval@localhost"
    commit_all "$1" "$2"
}

# A generation case's copy: the pieces, then the developer's entity and changeset (fixture/java under the root
# package, fixture/changes into the changelog and its master), committed as the baseline.
render_generation() {
    local case_dir="$1" dir="$2" id package app pieces module path
    id="$(basename "$case_dir")"
    app="${id%%-*}" package="com.acme.${id%%-*}" path="com/acme/${id%%-*}"
    pieces="$(tr -d '[:space:]' < "$case_dir/pieces")"
    # module: one line, "<module>: <what it owns>" (the review profile's ARCH-01 line).
    module="$(head -1 "$case_dir/module")"
    local aggregates='User (UserService); RefreshToken (TokenService)'
    render_copy "$dir" "$package" "$app" "$pieces" "user, ${module%%:*}" \
        "\`user: accounts and signing in\`, \`$module\`" "$aggregates"
    init_repo "$dir" "bootstrap ($pieces)"
    (cd "$case_dir/fixture/java" && find . -type f) | while read -r file; do
        mkdir -p "$(dirname "$dir/src/main/java/$path/$file")"
        sed "s/@ROOT_PACKAGE@/$package/g" "$case_dir/fixture/java/$file" > "$dir/src/main/java/$path/$file"
    done
    for changeset in "$case_dir"/fixture/changes/*.sql; do
        [ -f "$changeset" ] || continue
        cp "$changeset" "$dir/src/main/resources/db/changelog/changes/"
        printf '  - include:\n      file: changes/%s\n      relativeToChangelogFile: true\n' "$(basename "$changeset")" \
            >> "$dir/src/main/resources/db/changelog/db.changelog-master.yaml"
    done
    # A commit of its own, so the review's range (the bootstrap commit to the end) shows the entity and its changeset as
    # new: they have never run anywhere (the review profile's shippedRelease is none), so crud-gen may complete the
    # changeset in place and DATA-13 takes it for unreleased (the G1 calibration).
    commit_all "$dir" "The developer's entity and its changeset, not yet applied anywhere"
}

# The review copies' shared baseline, rendered once per run: the example piece, so every golden module is there.
review_base() {
    local dir="$1"
    [ -d "$dir" ] && return 0
    render_copy "$dir" "$REVIEW_PACKAGE" review "$REVIEW_PIECES" "user, todo, planning" \
        '`todo: a user'"'"'s todo lists, todos and tags`, `planning: days set aside for a todo`, `user: accounts and signing in`' \
        'User (UserService); RefreshToken (TokenService); TodoList (TodoListService); Todo (TodoService): TimeEntry, TodoTag; Tag (TagService); Plan (PlanService)'
    init_repo "$dir" "baseline"
}

# A review case's copy: the baseline, then defect.patch as a commit of its own, with the case's commit message.
render_review() {
    local case_dir="$1" dir="$2" base="$3" message
    review_base "$base"
    cp -a "$base" "$dir"
    git -C "$dir" update-index -q --refresh > /dev/null || true  # a copied index holds the original's file stats
    git -C "$dir" apply --index "$case_dir/defect.patch" || die "$(basename "$case_dir")/defect.patch does not apply to the review copy"
    message="$(section "$case_dir/case.md" "Commit message" | sed '/^[[:space:]]*$/d' | head -1)"
    git -C "$dir" commit -q -m "${message:-Change under review}"
}

# The body of a case.md section ("## <name>" to the next "## ").
section() {
    awk -v name="## $2" '$0 == name { on = 1; next } /^## / { on = 0 } on' "$1"
}

# ---- builds and model runs ---------------------------------------------------------------------------------------

# The harness's own build: never the agent's report. Writes <out> with the exit code, the test line and the seconds.
build() {
    local dir="$1" log="$2" out="$3" start exit=0 tests
    start=$(date +%s)
    (cd "$dir" && ./mvnw -o -B verify > "$log" 2>&1) || exit=$?
    tests="$(grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" "$log" | tail -1 | sed 's/^\[[A-Z]*\] //' || true)"
    printf '{"pass": %s, "exit": %d, "tests": "%s", "seconds": %d, "log": "%s",\n "errors": %s}\n' \
        "$([ "$exit" -eq 0 ] && echo true || echo false)" "$exit" "$tests" $(( $(date +%s) - start )) "$log" \
        "$(grep -E "Tests run:.*FAIL|\[ERROR\]   |\[ERROR\] (High|Medium|Low):|Rule violated|COMPILATION ERROR|BUILD FAILURE" "$log" | head -15 | python3 -I -c 'import json,sys; print(json.dumps(sys.stdin.read().splitlines()))')" \
        > "$out"
    return "$exit"
}

# An isolated HOME: only the snapshot's two skills and its reference are visible, every path the skills read under
# ~ points into the snapshot (the skills find the repository as their own directory's parent, which the links resolve
# to the snapshot), and the login and the Maven repository are the owner's (symlinks: nothing is copied).
isolated_home() {
    local home="$1"
    mkdir -p "$home/.claude/skills"
    ln -s "$SKILLS/crud-gen" "$home/.claude/skills/crud-gen"
    ln -s "$SKILLS/house-review" "$home/.claude/skills/house-review"
    ln -s "$REFERENCE" "$home/.claude/skills-reference"
    ln -s "$HOME/.claude/.credentials.json" "$home/.claude/.credentials.json"
    ln -s "$HOME/.m2" "$home/.m2"
    [ -e "$HOME/.testcontainers.properties" ] && ln -s "$HOME/.testcontainers.properties" "$home/.testcontainers.properties"
    return 0
}

# Read-only commands, the build and git without -C (no write outside the copy). The skills' own commands (the render
# the generator reads golden files through, git -C on the skills repository) are granted by their frontmatter's
# allowed-tools while they run. Edits need no rule: acceptEdits allows them inside the working directory only, and
# nothing else is granted (--permission-prompts none denies the rest, recorded in the run's permission denials).
READ_ONLY='Bash(ls *),Bash(ls),Bash(cat *),Bash(head *),Bash(tail *),Bash(grep *),Bash(rg *),Bash(wc *),Bash(diff *),Bash(sort *),Bash(uniq *),Bash(cut *),Bash(tr *),Bash(sed -n *),Bash(jq *),Bash(pwd),Bash(cd *),Bash(echo *),Bash(stat *),Bash(file *),Bash(tree *),Bash(which *),Bash(test *)'
GIT_READ='Bash(git status*),Bash(git diff*),Bash(git log*),Bash(git show*),Bash(git rev-parse*),Bash(git ls-files*),Bash(git branch*),Bash(git blame*)'
GEN_TOOLS="Bash(./mvnw *),$READ_ONLY,$GIT_READ,Bash(git add*),Bash(git commit*)"
REVIEW_TOOLS="Bash(./mvnw *),$READ_ONLY,$GIT_READ,Agent"
# Both skills' frontmatter grants only the commands their steps run (their allowed-tools), so a skill run no longer
# runs any command it likes (the G1 calibration, under a plain Bash grant, ran `python3 - <<EOF` edits and
# `git checkout -b`). The deny rules stay as the fence behind that list, since they win over any grant: the login,
# interpreters that write anywhere, and edits outside /tmp (the copies, the isolated HOMEs and the snapshot all live
# under $TMPDIR; the owner's home, the worktree included, doesn't). AskUserQuestion is denied too: --unattended
# already forbids it, and a question asked anyway would end the run unanswered.
DENIED='AskUserQuestion,Edit(//home/**),Write(//home/**),Read(**/.credentials.json),Bash(*credentials*),Bash(python*),Bash(*python3 *),Bash(*python -*),Bash(perl *),Bash(*perl -*),Bash(node *),Bash(bash -c*),Bash(sh -c*),Bash(*rm -rf*)'

# One headless model run: run_model <name> <case results> <work dir> <timeout s> <tools> <prompt> <system file> [schema]
run_model() {
    local name="$1" results="$2" dir="$3" seconds="$4" tools="$5" prompt="$6" system="$7" schema="${8:-}" exit=0
    local home="$WORK/homes/$(basename "$results")-$name"
    isolated_home "$home"
    local args=(-p "$prompt" --output-format stream-json --verbose --no-session-persistence
                --permission-mode acceptEdits --permission-prompts none --allowedTools "$tools"
                --disallowedTools "$DENIED" --max-budget-usd "$budget" --append-system-prompt "$(cat "$system")")
    [ -n "$schema" ] && args+=(--json-schema "$(cat "$schema")")
    [ -n "$model" ] && args+=(--model "$model")
    echo "    $name: claude -p '$prompt' (budget \$$budget, timeout ${seconds}s)"
    (cd "$dir" && HOME="$home" GIT_AUTHOR_NAME="eval harness" GIT_AUTHOR_EMAIL=eval@localhost \
        GIT_COMMITTER_NAME="eval harness" GIT_COMMITTER_EMAIL=eval@localhost \
        timeout "$seconds" claude "${args[@]}" > "$results/$name.jsonl" 2> "$results/$name.stderr") || exit=$?
    "${GATES[@]}" run "$results/$name.jsonl" "$results/$name.run.json" "$exit"
    python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); print("    %s: %s, $%.2f, %s turns, %.1f min" % (sys.argv[2], r.get("subtype"), r.get("total_cost_usd") or 0, r.get("num_turns"), (r.get("wall_ms") or r.get("duration_ms") or 0)/60000))' \
        "$results/$name.run.json" "$name"
}

# The house-review run of a reviewed scope, handed the harness's build of that code (Step 2, delegated reviewer).
review() {
    local results="$1" dir="$2" scope="$3" build_json="$4" summary system
    summary="$(python3 -I -c 'import json,sys; b=json.load(open(sys.argv[1])); print(("green" if b["exit"]==0 else "red, exit %d" % b["exit"]) + (": " + b["tests"] if b["tests"] else "") + ("; failures: " + " | ".join(b["errors"][:8]) if b["errors"] else ""))' "$build_json")"
    system="$results/house-review.system.md"
    BUILD="$summary" BUILD_LOG="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["log"])' "$build_json")" \
        perl -pe 's/\@([A-Z_]+)\@/exists $ENV{$1} ? $ENV{$1} : $&/ge' "$EVALS/lib/prompts/house-review.md" > "$system"
    run_model house-review "$results" "$dir" "$REVIEW_TIMEOUT" "$REVIEW_TOOLS" "/house-review $scope --unattended" "$system" "$EVALS/lib/review-schema.json"
}

note() {
    python3 -I -c 'import json,sys; p=sys.argv[1]; d=json.load(open(p)); d.setdefault("notes",[]).append(sys.argv[2]); json.dump(d,open(p,"w"),indent=2)' "$1/case.json" "$2"
}

generation_case() {
    local case_dir="$1" results="$2" dir="$3" entity baseline bootstrap system
    entity="src/main/java/com/acme/$(basename "$case_dir" | cut -d- -f1)/$(tr -d '[:space:]' < "$case_dir/entity")"
    render_generation "$case_dir" "$dir"
    baseline="$(git -C "$dir" rev-parse HEAD)" bootstrap="$(git -C "$dir" rev-parse HEAD~1)"
    system="$results/crud-gen.system.md"
    RULES="$(section "$case_dir/case.md" "Business rules")" \
        perl -pe 's/\@([A-Z_]+)\@/exists $ENV{$1} ? $ENV{$1} : $&/ge' "$EVALS/lib/prompts/crud-gen.md" > "$system"
    run_model crud-gen "$results" "$dir" "$GEN_TIMEOUT" "$GEN_TOOLS" "/crud-gen $entity --unattended" "$system"
    echo "    gate 2: the paths changed since the baseline"
    "${GATES[@]}" gate2 "$dir" "$baseline" "$case_dir/allowed" "com/acme/$(basename "$case_dir" | cut -d- -f1)" "$results/gate2.json"
    git -C "$dir" commit -q -m "crud-gen's change, as it left the tree" || true
    echo "    gate 1: ./mvnw -o -B verify by the harness"
    if build "$dir" "$results/gate1.log" "$results/gate1.json" || $always_review; then
        echo "    gate 3: house-review of $bootstrap..HEAD (the entity and crud-gen's change)"
        review "$results" "$dir" "commit $bootstrap..HEAD" "$results/gate1.json"
        "${GATES[@]}" gate3 "$results/house-review.run.json" "$results/gate3.json"
    else
        echo '{"pass": false, "note": "skipped: gate 1 is red (--always-review runs it anyway)"}' > "$results/gate3.json"
    fi
}

review_case() {
    local case_dir="$1" results="$2" dir="$3"
    render_review "$case_dir" "$dir" "$WORK/review-base"
    echo "    build of the reviewed commit by the harness"
    build "$dir" "$results/gate1.log" "$results/gate1.json" || true
    review "$results" "$dir" "commit $(git -C "$dir" rev-parse --short HEAD)" "$results/gate1.json"
    "${GATES[@]}" score "$results/house-review.run.json" "$case_dir/expected.json" "$results/score.json"
}

# ---- --check: fixtures render, patches apply, everything compiles; no model --------------------------------------

check_case() {
    local case_dir="$1" kind="$2" dir="$3" log="$4"
    if [ "$kind" = generation ]; then
        render_generation "$case_dir" "$dir"
        [ -f "$dir/src/main/java/com/acme/$(basename "$case_dir" | cut -d- -f1)/$(tr -d '[:space:]' < "$case_dir/entity")" ] \
            || die "$(basename "$case_dir"): the entity file isn't where $case_dir/entity says"
        [ -s "$(dirname "$case_dir")/$(basename "$case_dir")/allowed" ] || die "$(basename "$case_dir"): no allowed file"
    else
        render_review "$case_dir" "$dir" "$WORK/review-base"
        python3 -I -c 'import json,sys; e=json.load(open(sys.argv[1])); assert e.get("control") or e["expect"], "no expected finding"' "$case_dir/expected.json"
    fi
    (cd "$dir" && ./mvnw -o -q -B test-compile > "$log" 2>&1) || { grep -E "ERROR" "$log" | head -10 >&2; return 1; }
}

# ---- main --------------------------------------------------------------------------------------------------------

WORK="$(mktemp -d "${TMPDIR:-/tmp}/claude-skills-eval.XXXXXX")"
snapshot "$WORK/skills"
if [ -n "$prepare" ]; then
    [ -e "$prepare" ] && die "$prepare exists"
    review_base "$WORK/review-base"
    mv "$WORK/review-base" "$prepare"
    rm -rf "$WORK"
    echo "review copy at $prepare (package $REVIEW_PACKAGE, pieces $REVIEW_PIECES), at its baseline commit."
    echo "Make the defect, then: git -C $prepare diff > evals/review/<case>/defect.patch"
    exit 0
fi

selected=()
IFS=, read -ra patterns <<< "$globs"
for case_dir in "$EVALS"/generation/*/ "$EVALS"/review/*/; do
    [ -f "$case_dir/case.md" ] || continue
    id="$(basename "$case_dir")"
    for glob in "${patterns[@]}"; do
        # shellcheck disable=SC2053
        if [[ "$id" == $glob ]]; then selected+=("${case_dir%/}"); break; fi
    done
done
[ ${#selected[@]} -gt 0 ] || die "no case matches '$globs' (the cases: $(ls "$EVALS/generation" "$EVALS/review" | grep -v : | tr '\n' ' '))"

if $check; then
    failed=0
    for case_dir in "${selected[@]}"; do
        id="$(basename "$case_dir")" kind="$(basename "$(dirname "$case_dir")")"
        if check_case "$case_dir" "$kind" "$WORK/$id" "$WORK/$id.compile.log"; then
            echo "ok      $id ($kind: renders$([ "$kind" = review ] && echo ', the patch applies') and compiles)"
        else
            echo "FAILED  $id (work copy kept: $WORK/$id)"; failed=1
        fi
    done
    if [ "$failed" -eq 0 ] && ! $keep; then rm -rf "$WORK"; else echo "work copies: $WORK"; fi
    exit "$failed"
fi

command -v claude > /dev/null || die "claude is not on the PATH"
[ -f "$HOME/.claude/.credentials.json" ] || die "no login at ~/.claude/.credentials.json"
docker info > /dev/null 2>&1 || die "Docker is not reachable (Testcontainers). Start it, or run as 'sg docker -c $0'."

RESULTS="$EVALS/results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS" "$WORK/homes"
release_record "$RESULTS/release.txt"
echo "eval: ${#selected[@]} case(s), results in $RESULTS, work copies in $WORK"
for case_dir in "${selected[@]}"; do
    id="$(basename "$case_dir")" kind="$(basename "$(dirname "$case_dir")")"
    results="$RESULTS/$id" dir="$WORK/$id"
    mkdir -p "$results"
    printf '{"case": "%s", "kind": "%s", "work": "%s"}\n' "$id" "$kind" "$dir" > "$results/case.json"
    echo "[$id] $kind"
    start=$(date +%s)
    if [ "$kind" = generation ]; then
        generation_case "$case_dir" "$results" "$dir"
    else
        review_case "$case_dir" "$results" "$dir"
    fi
    wall=$(( $(date +%s) - start ))
    python3 -I -c 'import json,sys; p=sys.argv[1]; d=json.load(open(p)); d["wall"]="%.1f min" % (int(sys.argv[2])/60); json.dump(d,open(p,"w"),indent=2)' "$results/case.json" "$wall"
    verdict="$("${GATES[@]}" verdict "$results" "$kind")"
    echo "[$id] $verdict ($(( wall / 60 )) min)"
    if [ "$verdict" = pass ] && ! $keep; then
        rm -rf "$dir"
    else
        note "$results" "work copy kept: $dir"
    fi
done
rm -rf "$WORK/homes" "$WORK/review-base" "$WORK/skills"
"${GATES[@]}" summary "$RESULTS"
echo "summary: $RESULTS/summary.md"
