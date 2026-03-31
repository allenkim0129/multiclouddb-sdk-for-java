#!/usr/bin/env bash
# create-release-tag.sh — Create and push an annotated release tag.
# Usage: create-release-tag.sh --module <name> --version <version>
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
    echo "Usage: create-release-tag.sh --module <name> --version <version>" >&2
    exit 1
fi

TAG="${MODULE}-v${VERSION}"

echo "=== Creating release tag: $TAG ==="
echo ""

# Create annotated tag
git tag -a "$TAG" -m "Release ${MODULE} ${VERSION}"
echo "✅ Created annotated tag: $TAG"

# Push tag to origin
git push origin "$TAG"
echo "✅ Pushed tag to origin"

# Report details
COMMIT_SHA=$(git rev-parse HEAD)
echo ""
echo "=== Release Tag Summary ==="
echo "  Tag:     $TAG"
echo "  Commit:  $COMMIT_SHA"
echo "  Module:  $MODULE"
echo "  Version: $VERSION"

# Construct GitHub Actions URL
REMOTE_URL=$(git remote get-url origin 2>/dev/null || echo "")
if [[ "$REMOTE_URL" =~ github\.com[:/](.+)/(.+?)(\.git)?$ ]]; then
    OWNER="${BASH_REMATCH[1]}"
    REPO="${BASH_REMATCH[2]}"
    ACTIONS_URL="https://github.com/${OWNER}/${REPO}/actions"
    echo "  Actions: $ACTIONS_URL"
    echo ""
    echo "Monitor the release pipeline at the URL above."
    echo "The publish job requires manual approval in the 'production' environment."
else
    echo ""
    echo "Check GitHub Actions for the release pipeline status."
fi
