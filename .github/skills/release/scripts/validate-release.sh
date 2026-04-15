#!/usr/bin/env bash
# validate-release.sh — Pre-release validation checks for a module.
# Usage: validate-release.sh --module <name> --version <version>
set -euo pipefail

# ── Parse arguments ──────────────────────────────────────────────────────────
MODULE=""
VERSION=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --module)  MODULE="$2"; shift 2 ;;
        --version) VERSION="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

if [[ -z "$MODULE" || -z "$VERSION" ]]; then
    echo "Usage: validate-release.sh --module <name> --version <version>" >&2
    exit 1
fi

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
POM="$REPO_ROOT/pom.xml"
ERRORS=0

pass() { echo "  ✅ $1"; }
fail() { echo "  ❌ $1"; ERRORS=$((ERRORS + 1)); }

echo "=== Validating release: $MODULE v$VERSION ==="
echo ""

# ── Check 1: Valid publishable module ────────────────────────────────────────
VALID_MODULES="multiclouddb-api multiclouddb-provider-cosmos multiclouddb-provider-dynamo multiclouddb-provider-spanner"
if echo "$VALID_MODULES" | grep -qw "$MODULE"; then
    pass "Module '$MODULE' is a valid publishable module"
else
    fail "Module '$MODULE' is NOT a valid publishable module. Valid: $VALID_MODULES"
fi

# ── Check 2: Version follows semver ──────────────────────────────────────────
SEMVER_REGEX='^[0-9]+\.[0-9]+\.[0-9]+(-beta\.[0-9]+)?$'
if [[ "$VERSION" =~ $SEMVER_REGEX ]]; then
    pass "Version '$VERSION' follows semver pattern"
else
    fail "Version '$VERSION' does not match semver (X.Y.Z or X.Y.Z-beta.N)"
fi

# ── Check 3: POM version property matches ────────────────────────────────────
if [[ -f "$POM" ]]; then
    PROP_NAME="${MODULE}.version"
    POM_VERSION=$(sed -n "s/.*<${PROP_NAME}>\([^<]*\)<.*/\1/p" "$POM" 2>/dev/null | head -1)
    if [[ "$POM_VERSION" == "$VERSION" ]]; then
        pass "POM property <${PROP_NAME}> = '$POM_VERSION' matches requested version"
    elif [[ -z "$POM_VERSION" ]]; then
        fail "POM property <${PROP_NAME}> not found in root pom.xml"
    else
        fail "POM property <${PROP_NAME}> = '$POM_VERSION' does NOT match requested '$VERSION'. Update root pom.xml first."
    fi
else
    fail "Root pom.xml not found at $POM"
fi

# ── Check 4: Changelog has content ───────────────────────────────────────────
CHANGELOG="$REPO_ROOT/$MODULE/CHANGELOG.md"
if [[ -f "$CHANGELOG" ]]; then
    # Check if there's an [Unreleased] section with content, or a section for this version
    if grep -q "\[${VERSION}\]" "$CHANGELOG"; then
        pass "Changelog has a section for [$VERSION]"
    elif grep -q "\[Unreleased\]" "$CHANGELOG"; then
        # Check that [Unreleased] has content (at least one ### subsection)
        UNRELEASED_CONTENT=$(sed -n '/## \[Unreleased\]/,/## \[/p' "$CHANGELOG" | grep -c '^### ' || true)
        if [[ "$UNRELEASED_CONTENT" -gt 0 ]]; then
            pass "Changelog has [Unreleased] section with content (will be renamed to [$VERSION])"
        else
            fail "Changelog has [Unreleased] section but it appears empty. Add entries before releasing."
        fi
    else
        fail "Changelog has neither [Unreleased] nor [$VERSION] section"
    fi
else
    fail "Changelog not found at $CHANGELOG"
fi

# ── Check 5: Sibling dependency versions are valid releases ──────────────────
if [[ -f "$POM" ]]; then
    INVALID_DEPS=""
    for DEP in $VALID_MODULES; do
        if [[ "$DEP" == "$MODULE" ]]; then continue; fi
        DEP_PROP="${DEP}.version"
        DEP_VER=$(sed -n "s/.*<${DEP_PROP}>\([^<]*\)<.*/\1/p" "$POM" 2>/dev/null | head -1)
        if [[ -z "$DEP_VER" ]]; then continue; fi
        # Verify sibling version is a valid release (semver or semver-beta.N)
        if ! [[ "$DEP_VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-beta\.[0-9]+)?$ ]]; then
            INVALID_DEPS="$INVALID_DEPS $DEP=$DEP_VER"
        fi
    done
    if [[ -z "$INVALID_DEPS" ]]; then
        pass "All sibling dependency versions are valid releases"
    else
        fail "Sibling modules have non-release versions:$INVALID_DEPS. Only beta (X.Y.Z-beta.N) and GA (X.Y.Z) versions are supported."
    fi
fi

# ── Check 6: Tag doesn't already exist ───────────────────────────────────────
TAG="${MODULE}-v${VERSION}"
LOCAL_TAG=$(git tag -l "$TAG" 2>/dev/null || true)
if [[ -n "$LOCAL_TAG" ]]; then
    fail "Tag '$TAG' already exists locally. Delete it first: git tag -d $TAG"
else
    # Check remote
    REMOTE_TAG=$(git ls-remote --tags origin "refs/tags/$TAG" 2>/dev/null | head -1 || true)
    if [[ -n "$REMOTE_TAG" ]]; then
        fail "Tag '$TAG' already exists on remote. Delete it: git push origin :refs/tags/$TAG"
    else
        pass "Tag '$TAG' does not exist locally or on remote"
    fi
fi

# ── Check 7: On main branch and up to date ───────────────────────────────────
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")
if [[ "$CURRENT_BRANCH" == "main" ]]; then
    pass "On 'main' branch"
else
    fail "Not on 'main' branch (currently on '$CURRENT_BRANCH'). Run: git checkout main"
fi

# Fetch latest and compare
git fetch origin main --quiet 2>/dev/null || true
LOCAL_SHA=$(git rev-parse HEAD 2>/dev/null || echo "unknown")
REMOTE_SHA=$(git rev-parse origin/main 2>/dev/null || echo "unknown")
if [[ "$LOCAL_SHA" == "$REMOTE_SHA" ]]; then
    pass "Local main is up to date with origin/main"
else
    BEHIND=$(git rev-list --count HEAD..origin/main 2>/dev/null || echo "?")
    fail "Local main is $BEHIND commit(s) behind origin/main. Run: git pull origin main"
fi

# ── Check 8: Working tree is clean ───────────────────────────────────────────
if git diff --quiet && git diff --staged --quiet; then
    pass "Working tree is clean"
else
    fail "Working tree has uncommitted changes. Commit or stash them first."
fi

# ── Summary ──────────────────────────────────────────────────────────────────
echo ""
if [[ "$ERRORS" -eq 0 ]]; then
    echo "✅ All checks passed. Ready to release $MODULE v$VERSION"
    exit 0
else
    echo "❌ $ERRORS check(s) failed. Fix the issues above before releasing."
    exit 1
fi
