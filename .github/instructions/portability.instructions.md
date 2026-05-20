---
applyTo: "**"
---

# Portability & doc-alignment review checklist

This SDK exposes one portable contract over Cosmos DB, DynamoDB, and Spanner.
Cross-provider symmetry and documentation alignment are first-class invariants —
silently producing different behaviour across providers, or shipping a code
change without the matching doc/changelog update, are bugs.

Apply this checklist to every PR. Surface findings as comments — **do not edit
the PR**. For each gap, name the specific file (and section, if applicable)
where the missing change belongs.

## Provider symmetry

- If the public surface in `multiclouddb-api/` changed (new method, new field,
  new error category, new capability, new behaviour on an existing method),
  verify that **all three** providers handle it:
  `multiclouddb-provider-cosmos/`, `multiclouddb-provider-dynamo/`,
  `multiclouddb-provider-spanner/`.
- A provider that cannot implement the new behaviour MUST declare the gap via
  `CapabilitySet` and reject the operation with
  `MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY`. Silent divergence (different
  return value, different ordering, swallowed error) is never acceptable.
- New error paths must map to a portable `MulticloudDbErrorCategory` in every
  provider — not a provider-specific exception type.

## Conformance coverage

- Any new portable behaviour MUST be asserted in an abstract base in
  `multiclouddb-conformance/src/test/java/com/multiclouddb/conformance/`
  (e.g., `CrudConformanceTests`, `SortKeyOrderingConformanceTest`, or a new
  `us<N>/*ConformanceTest`).
- The corresponding per-provider subclasses
  (`CosmosConformanceTest`, `DynamoConformanceTest`, `SpannerConformanceTest`,
  or the per-feature subclasses under `us<N>/`) must be wired up — a new
  abstract test with no subclass is a no-op.
- Conformance assertions must stay provider-agnostic. Branching on
  `client.providerId()` inside an abstract base is a smell; if behaviour
  genuinely differs, the difference belongs in a `CapabilitySet` declaration,
  not a test branch.
- New capabilities must appear in `CapabilitiesConformanceTest` (supported or
  unsupported with the right error category) for every provider.

## Documentation alignment

For every code change, verify the matching docs are updated **in the same PR**:

| Change kind | Doc(s) that must update |
|---|---|
| New / changed public API in `multiclouddb-api` | `docs/guide.md`, `multiclouddb-api/CHANGELOG.md` |
| New behaviour in a provider adapter | per-provider `CHANGELOG.md` (e.g. `multiclouddb-provider-cosmos/CHANGELOG.md`) |
| New / changed config knob | `docs/configuration.md` |
| Any user-visible behaviour change | `docs/changelog.md` |
| Feature work under `specs/<NNN-feature>/` | the `spec.md` / `plan.md` / `tasks.md` reflect the *shipped* behaviour, not an earlier design |
| New example or runtime entry point | `multiclouddb-e2e/README.md` (and the relevant module README) |

CHANGELOG entries follow Keep a Changelog format and land under `[Unreleased]`
— flag any PR that adds a release-version header instead.

## What "review only" means here

- Comment on the gap; cite the file path and the section where the fix belongs.
- Do **not** push commits, apply suggested-changes blocks that mutate the diff,
  or restructure the PR. Suggesting the exact text of a missing CHANGELOG line
  in a comment is fine; landing it is the author's job.
- Style, formatting, and naming nits are out of scope unless they obscure the
  portability or doc-alignment story.
