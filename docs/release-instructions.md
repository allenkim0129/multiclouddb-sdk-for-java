# Release Instructions

This document describes how to release new versions of the Hyperscale DB SDK for Java.

## Overview

Releases are automated via the **Release** GitHub Actions workflow (`.github/workflows/release.yml`). The process is:

```
Create & push a version tag
        │
        ▼
┌─────────────────────────┐
│  3 parallel test gates  │  (automatic)
│  • Unit tests           │
│  • Cosmos DB emulator   │
│  • DynamoDB Local       │
└───────────┬─────────────┘
            │
      All 3 pass?
            │
┌───────────▼─────────────┐
│   Approval gate         │  (manual — production environment reviewers)
└───────────┬─────────────┘
            │
      Reviewer approves
            │
┌───────────▼─────────────┐
│   Publish               │  (automatic)
│   • Set Maven version   │
│   • Build & verify JARs │
│   • GitHub Release      │
└─────────────────────────┘
```

## Tag Format

Tags **must** follow one of these patterns or the release pipeline will not trigger:

| Pattern | Example | Use case |
|---------|---------|----------|
| `vX.Y.Z` | `v0.1.0`, `v1.0.0`, `v2.3.1` | Stable release |
| `vX.Y.Z-beta.N` | `v1.0.0-beta.1`, `v0.2.0-beta.3` | Beta / pre-release |

Tags that do **not** match (and will be ignored):
- `v1.0.0-rc1` — release candidates are not supported
- `v1.0.0-alpha.1` — alpha releases are not supported
- `release-1.0` — wrong prefix
- `1.0.0` — missing `v` prefix

## Step-by-Step Release Process

### 1. Prepare the release

Ensure `main` is in a releasable state:

- All PRs for this release are merged
- CI is green on `main`
- README and CHANGELOG are up to date

### 2. Create and push the tag

```bash
# For a stable release
git checkout main
git pull origin main
git tag v0.1.0
git push origin v0.1.0

# For a beta release
git tag v0.1.0-beta.1
git push origin v0.1.0-beta.1
```

> **Important:** Always tag from `main`. The tag name becomes the Maven version
> (with the `v` prefix stripped), so `v0.1.0` → version `0.1.0` in all POMs.

### 3. Monitor the test gates

Once the tag is pushed, the release pipeline starts automatically. Three test jobs run in parallel:

1. **Unit Tests** — runs `mvn verify -Punit` on Ubuntu
2. **Cosmos DB Emulator Tests** — starts the Azure Cosmos DB emulator on Windows and runs integration tests
3. **DynamoDB Local Tests** — starts DynamoDB Local via Docker and runs integration tests

Monitor progress at: **Actions → Release → (your run)**

If any gate fails:
- Check the test reports uploaded as workflow artifacts
- Fix the issue on `main`
- Delete the tag and re-tag:
  ```bash
  git tag -d v0.1.0
  git push origin :refs/tags/v0.1.0
  # After fix is merged to main:
  git checkout main
  git pull origin main
  git tag v0.1.0
  git push origin v0.1.0
  ```

### 4. Approve the release

Once all three gates pass, the publish job enters a **pending approval** state. Designated reviewers configured in the `production` GitHub environment will receive a notification.

To approve:
1. Go to the workflow run in **Actions**
2. Click **Review deployments**
3. Check the `production` environment
4. Click **Approve and deploy**

### 5. Verify the release

After approval, the publish job:

1. Sets the Maven version from the tag (e.g., `v0.1.0` → `0.1.0`)
2. Builds all modules and generates source + javadoc JARs
3. Verifies the 4 publishable artifacts are complete:
   - `hyperscaledb-api`
   - `hyperscaledb-provider-cosmos`
   - `hyperscaledb-provider-dynamo`
   - `hyperscaledb-provider-spanner`
4. Uploads the full Maven staging repository as a workflow artifact (90-day retention)
5. Creates a **GitHub Release** at `github.com/microsoft/hyperscale-db-sdk-for-java/releases/tag/vX.Y.Z` with:
   - All JARs (main, sources, javadoc) for the 4 publishable modules
   - Auto-generated release notes from merged PRs

### 6. Post-release

After a successful release:

- Verify the GitHub Release page looks correct
- Download a JAR from the release to spot-check
- Announce the release to stakeholders

## Published Artifacts

Each release produces the following Maven artifacts:

| Artifact | Contents |
|----------|----------|
| `com.hyperscaledb:hyperscaledb-api` | Portable API, SPI contracts, query expression model |
| `com.hyperscaledb:hyperscaledb-provider-cosmos` | Azure Cosmos DB adapter |
| `com.hyperscaledb:hyperscaledb-provider-dynamo` | Amazon DynamoDB adapter |
| `com.hyperscaledb:hyperscaledb-provider-spanner` | Google Cloud Spanner adapter |

Each artifact includes:
- `.jar` — compiled classes
- `-sources.jar` — source code for IDE navigation
- `-javadoc.jar` — generated API documentation
- `.pom` — Maven project descriptor

## Versioning Policy

This project follows [Semantic Versioning](https://semver.org/):

- **Major** (`X.0.0`) — breaking API changes
- **Minor** (`0.Y.0`) — new features, backward-compatible
- **Patch** (`0.0.Z`) — bug fixes, backward-compatible

Beta versions (`X.Y.Z-beta.N`) indicate pre-release builds that may have breaking changes between beta iterations.

## Environment Configuration

The `production` environment is configured in **Settings → Environments** with:

- **Required reviewers** — at least one designated reviewer must approve
- **Deployment branches/tags** — set the tag pattern to `v*` to allow all version tags

## Troubleshooting

### Pipeline didn't trigger after pushing a tag

- Verify the tag matches the expected pattern (`v1.2.3` or `v1.2.3-beta.1`)
- Check that the tag points to a commit on `main`
- Look in **Actions** for any workflow runs that may have been filtered

### Approval is stuck / no notification received

- Go to **Actions → Release → (your run)** and check if the publish job shows "Waiting for review"
- Ensure you are listed as a reviewer in **Settings → Environments → production**

### Maven version mismatch

The publish job runs `mvn versions:set` to align the POM version with the tag. If you see version mismatches in the artifacts, ensure the tag was created correctly and no manual POM version changes were made.
