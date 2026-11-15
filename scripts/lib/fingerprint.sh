# The fingerprint of what verify.sh tests: the git tree hashes of these paths. Sourced by
# scripts/verify.sh (which records it after a green run) and .githooks/pre-push (which compares the
# pushed commit with it), so a push needs no second, ten-minute verification of the same code.
VERIFIED_PATHS=(reference-app overlays scripts .githooks)

# Of the working copy, uncommitted and untracked files included (ignored ones such as target/ not).
working_fingerprint() {
    local repo="$1" index tree path
    index="$(mktemp)"
    rm -f "$index"
    GIT_INDEX_FILE="$index" git -C "$repo" -c core.safecrlf=false add -A -- "${VERIFIED_PATHS[@]}"
    tree="$(GIT_INDEX_FILE="$index" git -C "$repo" write-tree)"
    rm -f "$index"
    for path in "${VERIFIED_PATHS[@]}"; do
        git -C "$repo" rev-parse "$tree:$path"
    done
}

# Of a commit.
commit_fingerprint() {
    local repo="$1" commit="$2" path
    for path in "${VERIFIED_PATHS[@]}"; do
        git -C "$repo" rev-parse "$commit:$path"
    done
}

verified_record() {
    echo "$(git -C "$1" rev-parse --absolute-git-dir)/claude-skills-verified"
}
