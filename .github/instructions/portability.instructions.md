---
applyTo: "**"
---

# Portability & doc-alignment review checklist

This SDK exposes one portable contract over Cosmos DB, DynamoDB, and Spanner.
**Cross-provider symmetry and documentation alignment are first-class
invariants** — silently producing different behaviour across providers, or
shipping a code change without the matching doc/changelog update, are bugs.

Review with understanding, not with a mechanical checklist pass: read the diff,
form a model of the change, then use the list below to surface what's missing.
**Absence is often a stronger signal than presence** — a new public API with no
CHANGELOG entry, a new behaviour with no conformance assertion, a new error
path mapped to only two of three providers — those are the findings that matter.

## Severity tiers

Tag every finding with one of:

| Tier | Meaning |
|---|---|
| 🔴 **Blocking** | Must fix before merge. Correctness, portability violation, missing required doc/changelog, spec conformance gap. |
| 🟡 **Recommendation** | Should address. Design concern, missing test coverage, partial provider symmetry. |
| 🟢 **Suggestion** | Nice to have. Doesn't block merge. |
| 💬 **Observation** | Informational. No action required. |

Correctness and portability violations (silent divergence across providers,
unhandled error path in one adapter, missing `CapabilitySet` declaration) are
**always 🔴 Blocking** — never downgraded to suggestions.

## Provider symmetry (🔴 if violated)

- Public surface change in `multiclouddb-api/` (new method, new field, new
  error category, new capability, new behaviour on an existing method) →
  matching change in **all three** providers: `multiclouddb-provider-cosmos/`,
  `multiclouddb-provider-dynamo/`, `multiclouddb-provider-spanner/`.
- A provider that cannot implement the new behaviour MUST declare the gap via
  `CapabilitySet` and reject the operation with
  `MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY`. Silent divergence
  (different return value, different ordering, swallowed error) is never
  acceptable.
- New error paths must map to a portable `MulticloudDbErrorCategory` in every
  provider — not a provider-specific exception type.

## Conformance coverage (🔴 if portable behaviour has no assertion)

- New portable behaviour → assertion in an abstract base under
  `multiclouddb-conformance/src/test/java/com/multiclouddb/conformance/`
  (e.g., `CrudConformanceTests`, `SortKeyOrderingConformanceTest`, or a new
  `us<N>/*ConformanceTest`).
- Per-provider subclasses (`CosmosConformanceTest`, `DynamoConformanceTest`,
  `SpannerConformanceTest`, or per-feature subclasses under `us<N>/`) wired
  up — an abstract base with no subclass is a no-op.
- Conformance assertions stay provider-agnostic. Branching on
  `client.providerId()` inside an abstract base is a smell; gate via
  `CapabilitySet` + `Assumptions.assumeTrue(...)`, not an instance-of branch.
- New `Capability` constants appear in `CapabilitiesConformanceTest`
  (supported or unsupported with the right error category) for every provider.

## Spec conformance (🔴 if shipped behaviour contradicts spec)

For changes under `specs/<NNN-feature>/`:

- The implementation must faithfully realise the spec's behavioural
  requirements. Map spec statements → code paths; call out anything in the
  spec that's not implemented, and anything implemented that's not in the spec.
- If the design evolved during review (a common pattern in this repo —
  e.g., a parameter or mode was removed late), `spec.md` / `plan.md` /
  `tasks.md` must reflect the **shipped** behaviour, not the original design.

## Documentation alignment (🔴 if user-visible change ships without docs)

| Change kind | Doc(s) that must update in the same PR |
|---|---|
| New / changed public API in `multiclouddb-api` | `docs/guide.md` + `multiclouddb-api/CHANGELOG.md` (`[Unreleased]`) |
| New behaviour in a provider adapter | per-provider `CHANGELOG.md` (`[Unreleased]`) |
| New / changed config knob | `docs/configuration.md` |
| Any user-visible behaviour change | `docs/changelog.md` |
| Feature work under `specs/<NNN-feature>/` | `spec.md` / `plan.md` / `tasks.md` reflect *shipped* behaviour |
| New example or runtime entry point | `multiclouddb-e2e/README.md` + module README |

CHANGELOG entries follow Keep a Changelog format and land under `[Unreleased]`.
Flag any PR that adds a release-version header instead — that belongs to the
release workflow.

## What "review only" means here

- Comment on the gap. Cite the **exact file path** and (where applicable) the
  section/line where the missing change belongs.
- Quote the specific code or doc being discussed, explain **why it matters**
  (tie the consequence back to the portability or doc-alignment invariant),
  and offer a concrete suggestion when possible (e.g., the exact CHANGELOG
  line).
- **Do not push commits, do not apply suggested-changes blocks that mutate
  the diff, do not restructure the PR.** Suggesting the text of a missing
  CHANGELOG entry inside a review comment is fine; landing it is the
  author's job.
- Style, formatting, and naming nits are out of scope unless they obscure the
  portability or doc-alignment story.
