#!/usr/bin/env bash
# list-modules.sh — List publishable modules with their current versions.
# Run from the repository root.
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
POM="$REPO_ROOT/pom.xml"

if [[ ! -f "$POM" ]]; then
    echo "ERROR: Root pom.xml not found at $POM" >&2
    exit 1
fi

VALID_MODULES=(
    "hyperscaledb-api"
    "hyperscaledb-provider-cosmos"
    "hyperscaledb-provider-dynamo"
    "hyperscaledb-provider-spanner"
)

echo "=== Publishable Modules ==="
echo ""
printf "%-35s %-20s %s\n" "Module" "Current Version" "Tag"
printf "%-35s %-20s %s\n" "------" "---------------" "---"

for MODULE in "${VALID_MODULES[@]}"; do
    # Extract version property: <hyperscaledb-api.version>0.1.0-beta.1</...>
    PROP_NAME="${MODULE}.version"
    VERSION=$(sed -n "s/.*<${PROP_NAME}>\([^<]*\)<.*/\1/p" "$POM" 2>/dev/null | head -1)
    VERSION="${VERSION:-NOT FOUND}"

    if [[ "$VERSION" != "NOT FOUND" ]]; then
        TAG="${MODULE}-v${VERSION}"
    else
        TAG="N/A"
    fi

    printf "%-35s %-20s %s\n" "$MODULE" "$VERSION" "$TAG"
done

echo ""
echo "=== Dependency Order ==="
echo "1. hyperscaledb-api             ← release FIRST if API changed"
echo "2. hyperscaledb-provider-cosmos  ← then providers (independent of each other)"
echo "3. hyperscaledb-provider-dynamo"
echo "4. hyperscaledb-provider-spanner"
