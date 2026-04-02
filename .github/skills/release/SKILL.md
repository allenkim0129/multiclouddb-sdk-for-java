---
name: release
description: >
  Manages releases for hyperscaledb-sdk-for-java modules. Validates release
  readiness (POM versions, changelog entries, sibling dependency versions, tag
  uniqueness), updates changelogs, creates and pushes per-module version tags
  that trigger the automated release pipeline. Use when releasing modules,
  preparing a release, validating release readiness, creating release tags,
  or checking release status.
allowed-tools: bash git gh read_file edit
arguments:
  module:
    type: string
    required: true
    description: >
      Module to release. One of: hyperscaledb-api, hyperscaledb-provider-cosmos,
      hyperscaledb-provider-dynamo, hyperscaledb-provider-spanner.
  version:
    type: string
    required: true
    description: >
      Version to release following semver (e.g. 0.1.0-beta.1, 1.0.0).
  date:
    type: string
    required: false
    default: today
    description: >
      Release date in YYYY-MM-DD format. Defaults to today.
argument-hints:
  module:
    - hyperscaledb-api
    - hyperscaledb-provider-cosmos
    - hyperscaledb-provider-dynamo
    - hyperscaledb-provider-spanner
  version:
    - 0.1.0-beta.1
    - 0.1.0
    - 1.0.0
  date:
    - "2026-03-30"
---

# Release

Orchestrates the release of a hyperscaledb-sdk-for-java module by validating
readiness, updating the changelog, and creating/pushing a version tag to trigger
the automated release pipeline.

Core scripts are in `<THIS_SKILL_DIRECTORY>/scripts/` for deterministic behavior.
Reference material is in `<THIS_SKILL_DIRECTORY>/references/release-process.md`.

Path note: All script paths are relative to this skill directory (this SKILL.md
file), not the repository root.

## Prerequisites

- `git` configured with push access to the repository
- `gh` CLI installed and authenticated (`gh auth status`)
- Working directory is the repository root
- On the `main` branch with a clean working tree

## Workflow

Execute these phases in order. Stop and report if any phase fails.

### Phase 1: List Modules

Run the list-modules script to show current state:

```bash
<THIS_SKILL_DIRECTORY>/scripts/list-modules.sh
```

Present the output to the user. If the user hasn't specified a module or version,
use this information to help them decide.

### Phase 2: Validate Release Readiness

Run the validation script for the target module:

```bash
<THIS_SKILL_DIRECTORY>/scripts/validate-release.sh --module <MODULE> --version <VERSION>
```

If validation fails (non-zero exit), show all failures and stop. Provide the fix
instructions from the output. Do NOT proceed to Phase 3 until all checks pass.

### Phase 3: Update Changelog

Edit the module's changelog to prepare for release:

1. Read `<MODULE>/CHANGELOG.md`
2. Find the `## [Unreleased]` header line
3. Replace it with `## [<VERSION>] — <DATE>` (use the provided date or today)
4. Insert a new `## [Unreleased]` section above the renamed section with an
   empty line after it
5. Show the diff to the user for review
6. Commit with message: `chore(<MODULE>): prepare changelog for <VERSION> release`
7. Push the commit to `main`:
   ```bash
   git push origin main
   ```

**Important:** Wait for user confirmation before pushing. The commit must be on
`main` before the tag is created so the tagged commit includes the updated
changelog.

### Phase 4: Create and Push Tag

After the changelog commit is pushed, create the release tag:

```bash
<THIS_SKILL_DIRECTORY>/scripts/create-release-tag.sh --module <MODULE> --version <VERSION>
```

**Important:** Ask for explicit confirmation before running this step. Pushing the
tag immediately triggers the release pipeline.

### Phase 5: Monitor and Report

After the tag is pushed:

1. Check the release workflow status:
   ```bash
   gh run list --workflow=release.yml --limit=3
   ```

2. Report:
   - The tag name and commit SHA
   - The GitHub Actions workflow URL and status
   - Remind the user that the `production` environment requires manual approval
     in the GitHub UI: **Actions → Release → (the run) → Review deployments → Approve**

## Multi-Module Release

When releasing multiple modules, enforce dependency order:

1. Release `hyperscaledb-api` FIRST if it is in the release set
2. Then release providers in any order (they are independent of each other)
3. Run the full workflow (validate → changelog → tag) for each module sequentially

**Critical:** Push each tag individually with a separate `git push upstream <tag>`
command. Pushing multiple tags in a single `git push` silently fails to trigger
GitHub Actions workflows. Wait for each workflow run to appear in the Actions tab
before pushing the next tag.

If the API version changed and providers depend on the new version, the provider
POM properties must be updated and committed to `main` before releasing providers.

## Troubleshooting

Read `<THIS_SKILL_DIRECTORY>/references/release-process.md` for detailed
troubleshooting guidance covering version mismatches, dependency versions,
pipeline trigger failures, and approval issues.
