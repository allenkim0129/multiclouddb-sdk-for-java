# Release Instructions

This document describes how to release individual modules of the Hyperscale DB SDK for Java.

## Overview

Each publishable module has its own version and release cadence. Releases are
automated via the **Release** GitHub Actions workflow (`.github/workflows/release.yml`).

### Publishable Modules

| Module | Artifact ID | Description |
|--------|-------------|-------------|
| API | `hyperscaledb-api` | Portable contracts, SPI, query model |
| Cosmos | `hyperscaledb-provider-cosmos` | Azure Cosmos DB adapter |
| DynamoDB | `hyperscaledb-provider-dynamo` | Amazon DynamoDB adapter |
| Spanner | `hyperscaledb-provider-spanner` | Google Cloud Spanner adapter |

### Dependency Order

```
hyperscaledb-api  ← must be released first if API changed
    ↑
    ├── hyperscaledb-provider-cosmos   ← independent
    ├── hyperscaledb-provider-dynamo   ← independent
    └── hyperscaledb-provider-spanner  ← independent
```

Providers depend on a released version of `hyperscaledb-api`. If you change the
API, release it first, update the `hyperscaledb-api.version` property in the
root `pom.xml`, then release the providers.

### Development Cycle

During development, all modules live in a single Maven reactor and resolve
inter-module dependencies from **source** (reactor build). This means:

- Changes to `hyperscaledb-api` are immediately visible to all providers in the
  same build — no need for a published artifact during development.
- Version properties in the root `pom.xml` (e.g., `hyperscaledb-api.version`)
  always reflect the **next release version** (beta or GA). The reactor
  resolves these from the local build.
- When a PR changes both `hyperscaledb-api` and a provider, the CI reactor build
  validates them together.

At **release time**, the dependency ordering matters: if `hyperscaledb-api`
changed, it must be released (tagged) first so the provider release pipeline can
resolve the published artifact. Providers that don't need the new API version can
be released independently.

## Release Process

```
Create & push a per-module version tag
        │
        ▼
┌─────────────────────────┐
│  Conditional test gates  │  (automatic, per module)
│  • Unit tests (always)   │
│  • Cosmos emulator       │  ← api + provider-cosmos only
│  • DynamoDB Local        │  ← api + provider-dynamo only
└───────────┬──────────────┘
            │
      All gates pass?
            │
┌───────────▼──────────────┐
│   Approval gate          │  (manual — production environment reviewers)
└───────────┬──────────────┘
            │
┌───────────▼──────────────┐
│   Publish single module  │  (automatic)
│   • Verify POM version   │
│   • Verify deps released │
│   • Build & verify JARs  │
│   • GitHub Release       │
└──────────────────────────┘
```

## Tag Format

Tags identify **which module** and **what version** to release:

```
hyperscaledb-<module-name>-v<version>
```

| Pattern | Example | Use case |
|---------|---------|----------|
| Stable | `hyperscaledb-api-v1.0.0` | GA release |
| Beta | `hyperscaledb-provider-cosmos-v0.2.0-beta.1` | Pre-release |

Valid module names in tags:
- `hyperscaledb-api`
- `hyperscaledb-provider-cosmos`
- `hyperscaledb-provider-dynamo`
- `hyperscaledb-provider-spanner`

Tags that do **not** match (and will be ignored):
- `v1.0.0` — old format, missing module prefix
- `hyperscaledb-conformance-v1.0.0` — not a publishable module
- `hyperscaledb-api-v1.0.0-rc1` — release candidates not supported

## Step-by-Step: Releasing a Module

### 1. Prepare the release

Ensure `main` is in a releasable state:

- All PRs for this module's release are merged
- CI is green on `main`
- The module's `CHANGELOG.md` is up to date (move entries from `[Unreleased]`
  to a new version section)
- The module version in `pom.xml` matches what you intend to release
  (check the property in root `pom.xml`, e.g., `hyperscaledb-api.version`)

### 2. Create and push the tag

```bash
git checkout main
git pull origin main

# Release the API module
git tag hyperscaledb-api-v0.1.0-beta.1
git push origin hyperscaledb-api-v0.1.0-beta.1

# Or release a provider
git tag hyperscaledb-provider-cosmos-v0.1.0-beta.1
git push origin hyperscaledb-provider-cosmos-v0.1.0-beta.1
```

> **Important:** Always tag from `main`. The tag version must match the module's
> POM version exactly, or the publish job will fail.

### 3. Monitor the test gates

The release pipeline starts automatically. Test gates run based on the module:

| Module | Unit Tests | Cosmos Emulator | DynamoDB Local |
|--------|-----------|-----------------|----------------|
| `hyperscaledb-api` | ✅ | ✅ | ✅ |
| `hyperscaledb-provider-cosmos` | ✅ | ✅ | ⏭ skipped |
| `hyperscaledb-provider-dynamo` | ✅ | ⏭ skipped | ✅ |
| `hyperscaledb-provider-spanner` | ✅ | ⏭ skipped | ⏭ skipped |

If any gate fails:
- Check the test reports uploaded as workflow artifacts
- Fix the issue on `main`
- Delete the tag and re-tag:
  ```bash
  git tag -d hyperscaledb-api-v0.1.0-beta.1
  git push origin :refs/tags/hyperscaledb-api-v0.1.0-beta.1
  # After fix is merged to main:
  git checkout main && git pull origin main
  git tag hyperscaledb-api-v0.1.0-beta.1
  git push origin hyperscaledb-api-v0.1.0-beta.1
  ```

### 4. Approve the release

Once all gates pass, the publish job enters a **pending approval** state.
To approve: **Actions → Release → (your run) → Review deployments → Approve**.

### 5. Verify the release

After approval, the publish job:

1. Verifies the module's POM version matches the tag version
2. Verifies sibling dependency versions are valid releases (beta or GA)
3. Builds the full reactor, deploys only the target module
4. Verifies pom/jar/sources/javadoc artifacts
5. Uploads the Maven staging repository (90-day retention)
6. Creates a **GitHub Release** named `<module> <version>`

### 6. Post-release

- Verify the GitHub Release page
- If you released `hyperscaledb-api` and providers need the new version:
  1. Update `hyperscaledb-api.version` in root `pom.xml`
  2. Merge that change to `main`
  3. Then tag and release the providers

## Version Management

### Where versions live

| Location | Purpose |
|----------|---------|
| Root `pom.xml` → `<hyperscaledb-api.version>` | Declares the API version used by all modules |
| Root `pom.xml` → `<hyperscaledb-provider-*.version>` | Declares each provider's version |
| Module `pom.xml` → `<version>` | References the property from root |
| `<dependencyManagement>` | Uses these properties for inter-module deps |

### Bumping a version

1. Update the property in root `pom.xml` (e.g., `hyperscaledb-api.version`)
2. The module POM picks it up automatically via `${hyperscaledb-api.version}`
3. Commit, merge to `main`, then tag

### Versioning policy

This project follows [Semantic Versioning](https://semver.org/):

- **Major** (`X.0.0`) — breaking API changes
- **Minor** (`0.Y.0`) — new features, backward-compatible
- **Patch** (`0.0.Z`) — bug fixes, backward-compatible
- **Beta** (`X.Y.Z-beta.N`) — pre-release, may have breaking changes

## Example: Full Release Sequence

Release all modules for the first time:

```bash
# 1. Release the API first (providers depend on it)
git tag hyperscaledb-api-v0.1.0-beta.1
git push origin hyperscaledb-api-v0.1.0-beta.1
# Wait for pipeline to complete and approve

# 2. Release providers (can be done in parallel)
git tag hyperscaledb-provider-cosmos-v0.1.0-beta.1
git tag hyperscaledb-provider-dynamo-v0.1.0-beta.1
git tag hyperscaledb-provider-spanner-v0.1.0-beta.1
git push origin hyperscaledb-provider-cosmos-v0.1.0-beta.1
git push origin hyperscaledb-provider-dynamo-v0.1.0-beta.1
git push origin hyperscaledb-provider-spanner-v0.1.0-beta.1
```

## Troubleshooting

### "Version mismatch" error in publish job

The module's POM version doesn't match the tag. Update the version property in
root `pom.xml`, merge to `main`, delete the old tag, and re-tag.

### "Non-release dependency version" error in publish job

The module depends on a sibling with a non-release version. Only beta
(`X.Y.Z-beta.N`) and GA (`X.Y.Z`) versions are supported. Release the
dependency first (usually `hyperscaledb-api`), update the version property, then retry.

### Pipeline didn't trigger

- Verify the tag matches `hyperscaledb-*-v<semver>` or `hyperscaledb-*-v<semver>-beta.<N>`
- Check that the tag points to a commit on `main`

### Approval is stuck

Go to **Actions → Release → (your run)** and check if the publish job shows
"Waiting for review". Ensure you are listed as a reviewer in
**Settings → Environments → production**.

## Published Artifacts

Each module release produces:

| File | Contents |
|------|----------|
| `.jar` | Compiled classes |
| `-sources.jar` | Source code for IDE navigation |
| `-javadoc.jar` | Generated API documentation |
| `.pom` | Maven project descriptor |
